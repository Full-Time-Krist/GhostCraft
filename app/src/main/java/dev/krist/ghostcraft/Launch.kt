package dev.krist.ghostcraft

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object GameBridge {
    init {
        System.loadLibrary("ghostcraft")
    }

    // Blocks until the game's main() returns; System.exit() kills the process instead
    @JvmStatic
    external fun launch(javaHome: String, args: Array<String>, workDir: String, log: String): Int
}

object Launch {
    // ghostcraft-sync drops runtimes on shared storage, where .so files can't
    // be loaded: copy them to the app's internal storage when they change
    fun installJava(ctx: Context, major: Int, progress: (String) -> Unit): File =
        install(ctx, "jre-$major", "Java $major", listOf("lib/server/libjvm.so", "lib/modules"), progress)

    fun installLwjgl(ctx: Context, progress: (String) -> Unit): File? =
        if (!Store.runtimeDir(ctx).resolve("lwjgl/liblwjgl.so").isFile) null
        else install(ctx, "lwjgl", "LWJGL", null, progress)

    fun installMesa(ctx: Context, progress: (String) -> Unit): File? =
        if (!Store.runtimeDir(ctx).resolve("mesa/libEGL_mesa.so").isFile) null
        else install(ctx, "mesa", "Mesa", null, progress)

    // Native side of the graphics stack, read by our GLFW and by Mesa. Without
    // Mesa GLFW stays on the system EGL, which has OpenGL ES only.
    // instances/<id>/ghostcraft.env (KEY=VALUE lines) goes on top: Mesa and
    // Zink debug switches without rebuilding anything.
    fun environment(ctx: Context, inst: Instance, mesa: File?): Map<String, String> = buildMap {
        if (mesa != null) mesaEnvironment(ctx, mesa)
        inst.dir.resolve("ghostcraft.env").takeIf { it.isFile }?.readLines()?.forEach { line ->
            val (k, v) = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
                ?.split("=", limit = 2)?.takeIf { it.size == 2 } ?: return@forEach
            put(k.trim(), v.trim())
        }
    }

    private fun MutableMap<String, String>.mesaEnvironment(ctx: Context, mesa: File) {
        put("GHOSTCRAFT_EGL", mesa.resolve("libEGL_mesa.so").path)
        put("GHOSTCRAFT_GL", mesa.resolve("libGLESv2_mesa.so").path)
        // no DRM device in an app: Zink through EGL's surfaceless platform
        put("EGL_PLATFORM", "surfaceless")
        put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
        // Turnip, loaded straight into Zink: the system Vulkan of older Adreno
        // drivers lacks what Zink needs. It has no Android WSI, so frames are
        // copied into the window by our GLFW (runtime/mesa/build.sh).
        val turnip = mesa.resolve("libvulkan_freedreno.so")
        if (turnip.isFile) {
            put("ZINK_VULKAN_LIBRARY", turnip.path)
            put("GHOSTCRAFT_PRESENT", "copy")
        }
        put("MESA_SHADER_CACHE_DIR", ctx.cacheDir.resolve("mesa").apply { mkdirs() }.path)
    }

    private fun install(ctx: Context, name: String, title: String, keyFiles: List<String>?, progress: (String) -> Unit): File {
        val src = Store.runtimeDir(ctx).resolve(name)
        val dst = ctx.filesDir.resolve("runtime/$name")
        val stamp = dst.resolve(".source")
        val sig = signature(src, keyFiles) ?: error("$title не найдена на телефоне: ghostcraft-sync runtime")
        if (stamp.isFile && stamp.readText() == sig) return dst
        progress("Устанавливаю $title…")
        dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
        stamp.writeText(sig)
        return dst
    }

    // Size and mtime of the key files (all files when null), as tar left them
    private fun signature(dir: File, keyFiles: List<String>?): String? {
        val files = keyFiles?.map(dir::resolve) ?: dir.listFiles()?.filter(File::isFile)?.sortedBy { it.name }
        if (files.isNullOrEmpty() || files.any { !it.isFile }) return null
        return files.joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    fun logFile(inst: Instance): File = inst.gameDir.resolve("logs/ghostcraft-launch.log")

    // The same command line Prism builds from the recipe, plus what Android lacks
    fun command(ctx: Context, inst: Instance, nick: String): List<String> {
        val libs = Store.librariesDir(ctx)
        val natives = ctx.filesDir.resolve("runtime/lwjgl")
        val tmp = ctx.cacheDir.resolve("tmp").apply { mkdirs() }
        val glfw = "${ctx.applicationInfo.nativeLibraryDir}/libglfw.so"
        inst.gameDir.mkdirs()

        val classpath = (inst.classpath + listOfNotNull(inst.mainJar)).joinToString(":") { libs.resolve(it).path }
        val vars = mapOf(
            "auth_player_name" to nick,
            "auth_uuid" to UUID.nameUUIDFromBytes("OfflinePlayer:$nick".toByteArray()).toString().replace("-", ""),
            "auth_access_token" to "0",
            "auth_session" to "0",
            "user_type" to "legacy",
            "user_properties" to "{}",
            "version_name" to inst.minecraft,
            "version_type" to "GhostCraft",
            "game_directory" to inst.gameDir.path,
            "assets_root" to Store.assetsDir(ctx).path,
            "game_assets" to Store.assetsDir(ctx).path,
            "assets_index_name" to (inst.assetIndex ?: inst.minecraft),
            "library_directory" to libs.path,
            "classpath_separator" to ":",
            "natives_directory" to natives.path,
            "launcher_name" to "GhostCraft",
            "launcher_version" to "0.1",
        )
        fun expand(s: String) = Regex("""\$\{(\w+)\}""").replace(s) { vars[it.groupValues[1]] ?: it.value }

        val locale = Locale.getDefault()
        return buildList {
            add("java")
            add("-Xms${minOf(inst.memoryMin, heapLimit(ctx, inst))}M")
            add("-Xmx${heapLimit(ctx, inst)}M")
            // No /tmp, no /etc/localtime and no locale env on Android
            add("-Djava.io.tmpdir=${tmp.path}")
            add("-Duser.home=${Store.root(ctx).path}")
            add("-Duser.timezone=${TimeZone.getDefault().id}")
            add("-Duser.language=${locale.language}")
            add("-Duser.country=${locale.country}")
            add("-Djava.library.path=${natives.path}")
            add("-Dorg.lwjgl.librarypath=${natives.path}")
            // Our GLFW from the APK, shared with GameSurface. LWJGL's GL loader
            // goes to it too: it answers glXGetProcAddress through EGL
            add("-Dorg.lwjgl.glfw.libname=$glfw")
            add("-Dorg.lwjgl.opengl.libname=$glfw")
            // Shared storage can't hold loadable .so files; JNA unpacks its library here
            add("-Djna.tmpdir=${tmp.path}")
            // posix_spawn goes through lib/jspawnhelper, and Android forbids exec
            // from app data; vfork inside an ART process crashes the parent
            add("-Djdk.lang.Process.launchMechanism=FORK")
            // Phones have no RAM to spare and swap to zram: give heap back to
            // the system, dedupe strings (mods are full of them). GC log for
            // debugging: JDK_JAVA_OPTIONS=-Xlog:gc:stdout in ghostcraft.env
            add("-XX:+UseG1GC")
            add("-XX:+UseStringDeduplication")
            add("-XX:MinHeapFreeRatio=10")
            add("-XX:MaxHeapFreeRatio=25")
            add("-XX:G1PeriodicGCInterval=30000")
            // ForgeWrapper (NeoForge/Forge through Prism's meta) runs the
            // installer's processors on the first start and needs to know where
            // everything is; Prism passes the same properties
            if (inst.mainClass.startsWith("io.github.zekerzhayard.forgewrapper")) {
                add("-Dforgewrapper.librariesDir=${libs.path}")
                inst.installer?.let { add("-Dforgewrapper.installer=${libs.resolve(it).path}") }
                inst.mainJar?.let { add("-Dforgewrapper.minecraft=${libs.resolve(it).path}") }
            }
            addAll(inst.jvmArgs.map(::expand))
            addAll(inst.jvmArgsUser.split(Regex("\\s+")).filter { it.isNotEmpty() })
            add("-cp")
            add(classpath)
            add(inst.mainClass)
            addAll(inst.gameArgs.split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::expand))
        }
    }

    // The instance's -Xmx from Prism, but never more than half of the phone's RAM:
    // Android kills the whole app long before the JVM would notice
    fun heapLimit(ctx: Context, inst: Instance): Int {
        val info = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return minOf(inst.memoryMax, (info.totalMem / (1 shl 20) / 2).toInt())
    }
}
