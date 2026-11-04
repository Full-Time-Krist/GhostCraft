package dev.krist.ghostcraft

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

// Files sent by tools/ghostcraft-sync, laid out like Prism's data dir:
//   instances/<id>/ghostcraft.json   launch recipe resolved on the PC
//   instances/<id>/minecraft/        game dir (mods, config, saves…)
//   libraries/, assets/              shared stores
object Store {
    fun root(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    fun instancesDir(ctx: Context) = root(ctx).resolve("instances")
    fun librariesDir(ctx: Context) = root(ctx).resolve("libraries")
    fun assetsDir(ctx: Context) = root(ctx).resolve("assets")
    fun runtimeDir(ctx: Context) = root(ctx).resolve("runtime")
}

data class Loader(val uid: String, val name: String, val version: String)

class Instance(val dir: File, json: JSONObject) {
    val id: String = json.optString("id", dir.name)
    val name: String = json.optString("name", id)
    val group: String? = json.str("group")
    val minecraft: String = json.optString("minecraft", "?")
    val loader: Loader? = json.optJSONObject("loader")?.let {
        Loader(it.optString("uid"), it.optString("name"), it.optString("version"))
    }
    val javaMajor: Int = json.optInt("javaMajor", 21)
    val lwjgl: String = json.optJSONObject("lwjgl")?.optString("version") ?: "?"
    val lwjgl2: Boolean = json.optJSONObject("lwjgl")?.optString("uid") == "org.lwjgl"
    val memoryMax: Int = json.optJSONObject("memory")?.optInt("max", 2048) ?: 2048
    val memoryMin: Int = json.optJSONObject("memory")?.optInt("min", 512) ?: 512
    val mainClass: String = json.optString("mainClass")
    val mainJar: String? = json.str("mainJar")
    // NeoForge/Forge installer that ForgeWrapper runs on the first start
    val installer: String? = json.str("installer")
        ?: json.optJSONArray("mavenFiles").strings().firstOrNull { it.endsWith("-installer.jar") }
    val source: String = json.optString("source", "prism")
    val classpath: List<String> = json.optJSONArray("classpath").strings()
    val jvmArgs: List<String> = json.optJSONArray("jvmArgs").strings()
    val jvmArgsUser: String = json.optString("jvmArgsUser")
    val gameArgs: String = json.optString("gameArgs")
    val assetIndex: String? = json.optJSONObject("assetIndex")?.optString("id")?.ifEmpty { null }
    val warnings: List<String> = json.optJSONArray("warnings").strings()
    val syncedAt: Long = json.optLong("syncedAt") * 1000

    val gameDir: File get() = dir.resolve("minecraft")
    val modsDir: File get() = gameDir.resolve("mods")

    fun icon(): Bitmap? = gameDir.resolve("icon.png").takeIf { it.isFile }
        ?.let { BitmapFactory.decodeFile(it.path) }

    val subtitle: String
        get() = buildString {
            append("Minecraft ").append(minecraft)
            loader?.let { append(" · ").append(it.name.removeSuffix(" Loader")).append(' ').append(it.version) }
        }

    companion object {
        fun load(dir: File): Instance? = runCatching {
            Instance(dir, JSONObject(dir.resolve("ghostcraft.json").readText()))
        }.getOrNull()

        fun all(ctx: Context): List<Instance> =
            Store.instancesDir(ctx).listFiles()?.mapNotNull(::load)
                ?.sortedWith(compareBy({ it.group ?: "" }, { it.name.lowercase() }))
                ?: emptyList()
    }
}

// Python's None arrives as JSON null, which optString turns into "null"
private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else List(length()) { getString(it) }

// Mods that can't work in GhostCraft at all. They are switched off the way
// Prism does it (*.disabled), so the player sees it in the mod list.
object PhoneIncompatible {
    private val known = listOf(
        Regex("^ixeris", RegexOption.IGNORE_CASE) to
            "Ixeris переносит GLFW в свой поток, и касания перестают доходить до игры",
    )

    // Returns what was switched off and why
    fun disable(modsDir: java.io.File): List<String> =
        modsDir.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().mapNotNull { f ->
            val why = known.firstOrNull { it.first.containsMatchIn(f.name) }?.second ?: return@mapNotNull null
            if (f.renameTo(java.io.File(f.path + ".disabled"))) "${f.name}: $why" else null
        }
}

// A mod jar as Prism shows it: enabled unless renamed to *.disabled
data class Mod(val file: File, val name: String, val version: String?, val enabled: Boolean) {
    // Prism's convention, so toggles survive the next sync both ways
    fun toggled(): File {
        val target = if (enabled) File(file.path + ".disabled") else File(file.path.removeSuffix(".disabled"))
        file.renameTo(target)
        return target
    }

    companion object {
        private val cache = HashMap<String, Pair<String, String?>>()

        fun list(inst: Instance): List<Mod> =
            inst.modsDir.listFiles { f -> f.name.endsWith(".jar") || f.name.endsWith(".jar.disabled") }
                ?.map { f ->
                    val (name, ver) = describe(f)
                    Mod(f, name, ver, !f.name.endsWith(".disabled"))
                }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()

        // Display name from the jar's own metadata (Fabric, NeoForge/Forge, old Forge)
        private fun describe(f: File): Pair<String, String?> {
            val key = "${f.name.removeSuffix(".disabled")}:${f.length()}"
            cache[key]?.let { return it }
            val fallback = f.name.removeSuffix(".disabled").removeSuffix(".jar")
            val result = runCatching {
                ZipFile(f).use { zip ->
                    fun text(path: String) = zip.getEntry(path)?.let { zip.getInputStream(it).bufferedReader().readText() }
                    text("fabric.mod.json")?.let {
                        val o = JSONObject(it)
                        return@use o.optString("name", fallback) to o.optString("version").ifEmpty { null }
                    }
                    (text("META-INF/neoforge.mods.toml") ?: text("META-INF/mods.toml"))?.let { toml ->
                        val name = Regex("""displayName\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
                        val ver = Regex("""\bversion\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
                        return@use (name ?: fallback) to ver?.takeUnless { it.startsWith("$") }
                    }
                    text("mcmod.info")?.let {
                        val arr = it.trim().let { s -> if (s.startsWith("[")) JSONArray(s) else JSONObject(s).optJSONArray("modList") }
                        val o = arr?.optJSONObject(0)
                        return@use (o?.optString("name")?.ifEmpty { null } ?: fallback) to o?.optString("version")?.ifEmpty { null }
                    }
                    fallback to null
                }
            }.getOrDefault(fallback to null)
            cache[key] = result
            return result
        }
    }
}

data class World(val dir: File, val icon: File?, val modified: Long) {
    val name: String get() = dir.name

    companion object {
        fun list(inst: Instance): List<World> =
            inst.gameDir.resolve("saves").listFiles { f -> f.resolve("level.dat").isFile }
                ?.map { World(it, it.resolve("icon.png").takeIf(File::isFile), it.resolve("level.dat").lastModified()) }
                ?.sortedByDescending { it.modified }
                ?: emptyList()
    }
}

fun packs(inst: Instance, folder: String): List<File> =
    inst.gameDir.resolve(folder).listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()

// What the launch core still lacks on this phone (docs/ROADMAP.md, stage 2)
object Runtime {
    data class Part(val title: String, val detail: String, val ready: Boolean)

    fun check(ctx: Context, inst: Instance?): List<Part> {
        val rt = Store.runtimeDir(ctx)
        val java = inst?.javaMajor ?: 21
        val libs = Store.librariesDir(ctx)
        return listOf(
            Part("Java $java", "OpenJDK под Android arm64", rt.resolve("jre-$java/lib/server/libjvm.so").isFile),
            Part(
                "Библиотеки игры",
                inst?.let { "${it.classpath.size} jar с ПК" } ?: "Приходят с ПК через ghostcraft-sync",
                inst?.classpath?.all { libs.resolve(it).isFile } ?: libs.isDirectory,
            ),
            Part(
                if (inst?.lwjgl2 == true) "LWJGL 2 → 3 мост" else "LWJGL ${inst?.lwjgl ?: "3"}",
                "Нативные библиотеки arm64: ядро, OpenGL, STB, OpenAL, FreeType",
                rt.resolve("lwjgl/liblwjgl.so").isFile && inst?.lwjgl2 != true,
            ),
            // ours, built into the APK
            Part("GLFW поверх Android", "Окно, ввод и контекст GL через SurfaceView и EGL", true),
            Part("OpenGL (Zink + Turnip)", "Mesa: desktop GL поверх Vulkan Adreno", rt.resolve("mesa/libEGL_mesa.so").isFile),
        )
    }

    // Java and the jars are enough to start the JVM and watch the game boot,
    // even before the graphics stack exists
    fun canStart(parts: List<Part>) = parts.take(2).all { it.ready }
}
