package dev.krist.ghostcraft

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Creating instances on the phone, without the PC: the same recipe
// tools/ghostcraft-sync builds from Prism, made from Prism's meta server
// (meta.prismlauncher.org), so vanilla, Fabric, Quilt, NeoForge and Forge
// all come out in one format. Files land in the shared stores as Prism lays
// them out, so a later sync from the PC reuses them.

object Net {
    private const val UA = "Full-Time-Krist/GhostCraft/0.1 (Android launcher)"

    fun open(url: String, readTimeout: Int = 30_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = readTimeout
        c.setRequestProperty("User-Agent", UA)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException("HTTP $code: $url")
        }
        return c
    }

    fun text(url: String): String = open(url).inputStream.use { it.readBytes().decodeToString() }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // A connection that never comes up is usually a blocked host (NeoForge's
    // Maven for some providers): say so instead of a bare timeout
    fun download(url: String, dst: File, sha1: String? = null) {
        try {
            fetch(url, dst, sha1)
        } catch (e: java.net.SocketTimeoutException) {
            val host = url.substringAfter("//").substringBefore('/')
            throw IOException("$host не отвечает — возможно, заблокирован: включи VPN на телефоне", e)
        }
    }

    // Through a .part file, checked against sha1 when given; three tries
    private fun fetch(url: String, dst: File, sha1: String?, tries: Int = 3, readTimeout: Int = 30_000) {
        var last: Exception? = null
        repeat(tries) {
            try {
                dst.parentFile?.mkdirs()
                val part = File(dst.path + ".part")
                val md = MessageDigest.getInstance("SHA-1")
                open(url, readTimeout).inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (sha1 != null && !got.equals(sha1, ignoreCase = true)) {
                    part.delete()
                    throw IOException("SHA-1 не совпал: ${dst.name}")
                }
                if (!part.renameTo(dst)) throw IOException("Не переименовать ${part.name}")
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw (last as? IOException) ?: IOException(last)
    }
}

// A file to fetch: libraries and assets are skipped when already there
data class Download(val url: String, val file: File, val sha1: String?, val size: Long) {
    val present: Boolean get() = file.isFile && (size <= 0 || file.length() == size)
}

// One long job at a time, shown on the home screen
object Jobs {
    data class State(
        val title: String,
        val step: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val error: String? = null,
        val finished: Boolean = false,
    ) {
        val running get() = error == null && !finished
    }

    val state = MutableStateFlow<State?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(title: String, block: suspend Progress.() -> Unit): Boolean {
        if (state.value?.running == true) return false
        state.value = State(title)
        cancelled = false
        scope.launch {
            try {
                Progress(title).block()
                state.value = state.value?.copy(finished = true, step = "Готово")
            } catch (e: Throwable) {
                android.util.Log.w("GhostCraft-Net", "job failed", e)
                state.value = state.value?.copy(error = if (cancelled) "Отменено" else e.message ?: e.toString())
            }
        }
        return true
    }

    fun dismiss() {
        if (state.value?.running != true) state.value = null
    }

    @Volatile
    var cancelled = false

    fun cancel() {
        cancelled = true
    }

    class Progress(private val title: String) {
        fun step(text: String, done: Int = 0, total: Int = 0) {
            state.value = State(title, text, done, total)
        }

        // Parallel downloads with a live counter
        fun fetch(text: String, files: List<Download>) {
            val todo = files.distinctBy { it.file.path }.filterNot { it.present }
            if (todo.isEmpty()) return
            val done = AtomicInteger()
            step(text, 0, todo.size)
            val pool = Executors.newFixedThreadPool(12)
            try {
                val futures = todo.map { d ->
                    pool.submit {
                        if (cancelled) throw java.util.concurrent.CancellationException()
                        android.util.Log.i("GhostCraft-Net", "get ${d.url}")
                        try {
                            Net.download(d.url, d.file, d.sha1)
                        } catch (e: Exception) {
                            android.util.Log.w("GhostCraft-Net", "fail ${d.url}: $e")
                            throw IOException("${d.file.name}: ${e.message}", e)
                        }
                        step(text, done.incrementAndGet(), todo.size)
                    }
                }
                for (f in futures) {
                    // polled, so a cancel gets through a hung download
                    while (true) {
                        if (cancelled) throw java.util.concurrent.CancellationException("Отменено")
                        try {
                            f.get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                            break
                        } catch (_: java.util.concurrent.TimeoutException) {
                        }
                    }
                }
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } finally {
                pool.shutdownNow()
            }
        }
    }
}

// --- Prism meta ------------------------------------------------------------------

data class MetaVersion(
    val uid: String,
    val version: String,
    val type: String,
    val recommended: Boolean,
    val releaseTime: String,
    val requires: Map<String, String?>,   // uid → exact version, if any
    val suggests: Map<String, String> = emptyMap(),
)

object Meta {
    private const val BASE = "https://meta.prismlauncher.org/v1"
    private const val INDEX_TTL = 6 * 3600_000L

    private fun dir(ctx: Context, uid: String) = Store.root(ctx).resolve("meta/$uid")

    fun index(ctx: Context, uid: String): List<MetaVersion> {
        val f = dir(ctx, uid).resolve("index.json")
        val text = if (f.isFile && System.currentTimeMillis() - f.lastModified() < INDEX_TTL) f.readText()
        else runCatching { Net.text("$BASE/$uid/index.json").also { f.parentFile?.mkdirs(); f.writeText(it) } }
            .getOrElse { if (f.isFile) f.readText() else throw it }
        val arr = JSONObject(text).getJSONArray("versions")
        return List(arr.length()) { i ->
            val v = arr.getJSONObject(i)
            val req = v.optJSONArray("requires")
            MetaVersion(
                uid, v.getString("version"), v.optString("type", "release"), v.optBoolean("recommended"),
                v.optString("releaseTime"),
                buildMap {
                    if (req != null) for (j in 0 until req.length()) {
                        val r = req.getJSONObject(j)
                        put(r.getString("uid"), r.optString("equals").ifEmpty { null })
                    }
                },
                buildMap {
                    if (req != null) for (j in 0 until req.length()) {
                        val r = req.getJSONObject(j)
                        r.optString("suggests").ifEmpty { null }?.let { put(r.getString("uid"), it) }
                    }
                },
            )
        }
    }

    fun version(ctx: Context, uid: String, version: String): JSONObject {
        val f = dir(ctx, uid).resolve("$version.json")
        if (!f.isFile) {
            f.parentFile?.mkdirs()
            f.writeText(Net.text("$BASE/$uid/$version.json"))
        }
        return JSONObject(f.readText())
    }
}

// --- What can run here -------------------------------------------------------------

enum class LoaderKind(val title: String, val uid: String?) {
    VANILLA("Vanilla", null),
    FABRIC("Fabric", "net.fabricmc.fabric-loader"),
    QUILT("Quilt", "org.quiltmc.quilt-loader"),
    NEOFORGE("NeoForge", "net.neoforged"),
    FORGE("Forge", "net.minecraftforge"),
}

object Compat {
    // Our arm64 natives are LWJGL 3.3.3, and every game gets those jars
    const val LWJGL = "3.3.3"

    fun javaAvailable(ctx: Context): List<Int> =
        Store.runtimeDir(ctx).listFiles()?.mapNotNull { it.name.removePrefix("jre-").toIntOrNull() }?.sorted()
            ?: emptyList()

    // Why a Minecraft version can't start here, or null when it can
    fun problem(ctx: Context, v: MetaVersion): String? {
        if ("org.lwjgl" in v.requires) return "LWJGL 2 (до 1.13) пока не поддерживается"
        val meta = runCatching { Meta.version(ctx, "net.minecraft", v.version) }.getOrNull() ?: return null
        val majors = meta.optJSONArray("compatibleJavaMajors")
        val need = if (majors == null) 8 else List(majors.length()) { majors.getInt(it) }.min()
        val have = javaAvailable(ctx).maxOrNull() ?: 21
        return if (need > have) "Нужна Java $need, на телефоне Java $have" else null
    }

    // Quick check from the index alone, for the version list: LWJGL 2 games,
    // and 26.x with LWJGL 3.4, which came with Java 25
    fun quickProblem(v: MetaVersion): String? = when {
        "org.lwjgl" in v.requires -> "LWJGL 2"
        (v.suggests["org.lwjgl3"] ?: "").let { it.startsWith("3.4") || it.startsWith("3.5") } -> "Java 25"
        else -> null
    }

    // Loader versions that fit a Minecraft version, newest first
    fun loaderVersions(ctx: Context, kind: LoaderKind, mc: String): List<MetaVersion> {
        val uid = kind.uid ?: return emptyList()
        val all = Meta.index(ctx, uid)
        return when (kind) {
            // Fabric and Quilt take any game version through intermediary
            LoaderKind.FABRIC, LoaderKind.QUILT -> {
                val inter = runCatching { Meta.index(ctx, "net.fabricmc.intermediary") }.getOrDefault(emptyList())
                if (inter.none { it.version == mc }) emptyList() else all.filter { it.type == "release" || kind == LoaderKind.QUILT }
            }
            else -> all.filter { it.requires["net.minecraft"] == mc }
        }
    }
}

// --- The recipe ------------------------------------------------------------------------

data class Component(val uid: String, val version: String)

object Recipes {
    fun components(mc: String, kind: LoaderKind, loaderVersion: String?): List<Component> = buildList {
        add(Component("org.lwjgl3", Compat.LWJGL))
        add(Component("net.minecraft", mc))
        when (kind) {
            LoaderKind.FABRIC, LoaderKind.QUILT -> {
                add(Component("net.fabricmc.intermediary", mc))
                add(Component(kind.uid!!, loaderVersion!!))
            }
            LoaderKind.NEOFORGE, LoaderKind.FORGE -> add(Component(kind.uid!!, loaderVersion!!))
            LoaderKind.VANILLA -> Unit
        }
    }

    private fun mavenPath(name: String): String {
        var n = name
        var ext = "jar"
        if ('@' in n) {
            ext = n.substringAfter('@')
            n = n.substringBefore('@')
        }
        val p = n.split(':')
        val classifier = if (p.size > 3) "-${p[3]}" else ""
        return "${p[0].replace('.', '/')}/${p[1]}/${p[2]}/${p[1]}-${p[2]}$classifier.$ext"
    }

    // Mojang rules judged as Linux, the closest desktop to Android
    private fun allowed(rules: JSONArray?): Boolean {
        if (rules == null) return true
        var ok = false
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val os = r.optJSONObject("os")?.optString("name")?.ifEmpty { null }
            if (os == null || os == "linux") ok = r.optString("action") == "allow"
        }
        return ok
    }

    private fun libPath(lib: JSONObject): String =
        lib.optJSONObject("downloads")?.optJSONObject("artifact")?.optString("path")?.ifEmpty { null }
            ?: mavenPath(lib.getString("name"))

    private fun libDownload(libs: File, lib: JSONObject): Download {
        val path = libPath(lib)
        val art = lib.optJSONObject("downloads")?.optJSONObject("artifact")
        val url = art?.optString("url")?.ifEmpty { null }
            ?: (lib.optString("url").ifEmpty { "https://libraries.minecraft.net/" }.trimEnd('/') + "/" + mavenPath(lib.getString("name")))
        return Download(url, libs.resolve(path), art?.optString("sha1")?.ifEmpty { null }, art?.optLong("size") ?: -1)
    }

    class Resolved(val recipe: JSONObject, val files: List<Download>, val assetIndex: JSONObject?)

    // tools/ghostcraft-sync resolve(), on the phone
    fun resolve(ctx: Context, comps: List<Component>, id: String, name: String, group: String?): Resolved {
        val libs = Store.librariesDir(ctx)
        val metas = comps.map { Meta.version(ctx, it.uid, it.version) }.sortedBy { it.optInt("order") }
        val classpath = ArrayList<String>()
        val seen = HashMap<String, Int>()
        val maven = ArrayList<String>()
        val files = ArrayList<Download>()
        val jvmArgs = JSONArray()
        val warnings = JSONArray()
        var mainClass: String? = null
        var mainJar: String? = null
        var gameArgs = ""
        var assetIndex: JSONObject? = null
        var mc: String? = null
        var loader: JSONObject? = null
        var installer: String? = null
        val tweakers = ArrayList<String>()

        for (m in metas) {
            val uid = m.getString("uid")
            when (uid) {
                "net.minecraft" -> {
                    mc = m.getString("version")
                    val majors = m.optJSONArray("compatibleJavaMajors")
                    val need = if (majors == null) 8 else List(majors.length()) { majors.getInt(it) }.max()
                    if (need < 17) warnings.put("Minecraft $mc рассчитан на Java $need, запускается на Java 21")
                }
                "org.lwjgl3", "org.lwjgl", "net.fabricmc.intermediary" -> Unit
                else -> loader = JSONObject().put("uid", uid).put("name", m.optString("name", uid)).put("version", m.getString("version"))
            }
            m.optString("mainClass").ifEmpty { null }?.let { mainClass = it }
            m.optString("minecraftArguments").ifEmpty { null }?.let { gameArgs = it }
            m.optJSONObject("assetIndex")?.let { assetIndex = it }
            m.optJSONObject("mainJar")?.let {
                mainJar = libPath(it)
                files += libDownload(libs, it)
            }
            m.optJSONArray("+jvmArgs")?.let { for (i in 0 until it.length()) jvmArgs.put(it.getString(i)) }
            m.optJSONArray("+tweakers")?.let { for (i in 0 until it.length()) tweakers += it.getString(i) }
            m.optJSONArray("libraries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val lib = arr.getJSONObject(i)
                    if (!allowed(lib.optJSONArray("rules"))) continue
                    val libName = lib.getString("name")
                    // desktop natives are useless here: GhostCraft has its own arm64 builds
                    if (lib.has("natives") || ":natives-" in libName) continue
                    val parts = libName.split(':')
                    val key = (parts.take(2) + parts.drop(3).take(1)).joinToString(":")
                    val path = libPath(lib)
                    val at = seen[key]
                    if (at != null) classpath[at] = path else {
                        seen[key] = classpath.size
                        classpath += path
                    }
                    files += libDownload(libs, lib)
                }
            }
            m.optJSONArray("mavenFiles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val lib = arr.getJSONObject(i)
                    val path = libPath(lib)
                    maven += path
                    if (lib.getString("name").endsWith(":installer")) installer = path
                    files += libDownload(libs, lib)
                }
            }
        }
        if (tweakers.isNotEmpty()) gameArgs += tweakers.joinToString("") { " --tweakClass $it" }
        // a library replaced by a later component isn't needed
        val wanted = (classpath + maven + listOfNotNull(mainJar)).map { libs.resolve(it).path }.toSet()

        val recipe = JSONObject()
            .put("format", 1)
            .put("id", id)
            .put("name", name)
            .put("group", group ?: JSONObject.NULL)
            .put("minecraft", mc)
            .put("loader", loader ?: JSONObject.NULL)
            .put("lwjgl", JSONObject().put("uid", "org.lwjgl3").put("version", Compat.LWJGL))
            .put("memory", JSONObject().put("min", 512).put("max", 4096))
            .put("jvmArgsUser", "")
            .put("classpath", JSONArray(classpath))
            .put("mavenFiles", JSONArray(maven))
            .put("installer", installer ?: JSONObject.NULL)
            .put("mainClass", mainClass)
            .put("mainJar", mainJar ?: JSONObject.NULL)
            .put("gameArgs", gameArgs)
            .put("jvmArgs", jvmArgs)
            .put("assetIndex", assetIndex?.let { JSONObject().put("id", it.getString("id")) } ?: JSONObject.NULL)
            .put("javaMajor", Compat.javaAvailable(ctx).maxOrNull() ?: 21)
            .put("warnings", warnings)
            .put("components", JSONArray(comps.map { JSONObject().put("uid", it.uid).put("version", it.version) }))
            .put("source", "phone")
            .put("syncedAt", System.currentTimeMillis() / 1000)
        return Resolved(recipe, files.filter { it.file.path in wanted }, assetIndex)
    }

    // Asset index plus every object it lists
    fun assets(ctx: Context, index: JSONObject?): List<Download> {
        index ?: return emptyList()
        val dir = Store.assetsDir(ctx)
        val f = dir.resolve("indexes/${index.getString("id")}.json")
        if (!f.isFile) Net.download(index.getString("url"), f, index.optString("sha1").ifEmpty { null })
        val objects = JSONObject(f.readText()).getJSONObject("objects")
        return objects.keys().asSequence().map { k ->
            val o = objects.getJSONObject(k)
            val h = o.getString("hash")
            Download("https://resources.download.minecraft.net/${h.take(2)}/$h", dir.resolve("objects/${h.take(2)}/$h"), h, o.optLong("size"))
        }.toList()
    }

    // What a phone needs from the first start: the system language, short
    // distances (RAM, not the GPU, runs out first) and no accessibility
    // onboarding. A pack's own options.txt keeps everything else.
    fun phoneOptions(file: File) {
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        fun get(k: String) = lines.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')
        fun set(k: String, v: String) {
            val i = lines.indexOfFirst { it.startsWith("$k:") }
            if (i >= 0) lines[i] = "$k:$v" else lines += "$k:$v"
        }
        if (get("lang") == null) {
            val l = java.util.Locale.getDefault()
            set("lang", "${l.language}_${l.country}".lowercase())
        }
        for (k in listOf("renderDistance", "simulationDistance")) {
            val v = get(k)?.toIntOrNull()
            if (v == null || v > 6) set(k, "6")
        }
        set("onboardAccessibility", "false")
        set("skipMultiplayerWarning", "true")
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    // A folder name that's free and safe on any filesystem
    fun newId(ctx: Context, name: String): String {
        val base = name.replace(Regex("""[^\p{L}\p{N}._ -]"""), "").trim().replace(' ', '-').ifEmpty { "instance" }.take(48)
        val dir = Store.instancesDir(ctx)
        var id = base
        var n = 2
        while (dir.resolve(id).exists()) id = "$base-${n++}"
        return id
    }

    // Everything for one instance: recipe, libraries, game jar, assets
    fun install(ctx: Context, p: Jobs.Progress, comps: List<Component>, name: String, group: String? = null, gameSetup: (File) -> Unit = {}): String {
        p.step("Метаданные версий…")
        val id = newId(ctx, name)
        val r = resolve(ctx, comps, id, name, group)
        p.fetch("Библиотеки и игра", r.files)
        p.step("Индекс ресурсов…")
        p.fetch("Ресурсы: звуки и языки", assets(ctx, r.assetIndex))
        val dir = Store.instancesDir(ctx).resolve(id)
        try {
            dir.resolve("minecraft").mkdirs()
            gameSetup(dir.resolve("minecraft"))
            phoneOptions(dir.resolve("minecraft/options.txt"))
            // last, so a half-made instance never shows up in the list
            dir.resolve("ghostcraft.json").writeText(r.recipe.toString(2))
        } catch (e: Throwable) {
            // shared libraries and assets stay for the next try; the half-made game folder goes
            dir.deleteRecursively()
            throw e
        }
        return id
    }
}
