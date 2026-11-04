package dev.krist.ghostcraft

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

// Modrinth modpacks (.mrpack): modrinth.index.json lists the mods with their
// download links and the game/loader versions; overrides/ holds configs.
object Modrinth {
    private const val API = "https://api.modrinth.com/v2"

    data class Pack(
        val id: String,
        val title: String,
        val description: String,
        val iconUrl: String?,
        val downloads: Long,
        val gameVersions: List<String>,
        val loaders: List<String>,
    )

    data class PackVersion(
        val id: String,
        val name: String,
        val number: String,
        val gameVersions: List<String>,
        val loaders: List<String>,
        val fileUrl: String,
        val fileSha1: String?,
        val published: String,
    )

    private val LOADERS = setOf("fabric", "quilt", "neoforge", "forge")

    fun search(query: String, offset: Int = 0): List<Pack> {
        val facets = Net.encode("""[["project_type:modpack"]]""")
        val index = if (query.isBlank()) "downloads" else "relevance"
        val o = JSONObject(Net.text("$API/search?query=${Net.encode(query)}&facets=$facets&index=$index&limit=20&offset=$offset"))
        val hits = o.getJSONArray("hits")
        return List(hits.length()) { i ->
            val h = hits.getJSONObject(i)
            val cats = h.optJSONArray("categories").strings()
            Pack(
                h.getString("project_id"), h.getString("title"), h.optString("description"),
                h.optString("icon_url").ifEmpty { null }, h.optLong("downloads"),
                h.optJSONArray("versions").strings(), cats.filter { it in LOADERS },
            )
        }
    }

    fun versions(projectId: String): List<PackVersion> {
        val arr = JSONArray(Net.text("$API/project/$projectId/version"))
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.getJSONObject(i)
            val files = v.getJSONArray("files")
            val file = (0 until files.length()).map(files::getJSONObject)
                .let { fs -> fs.firstOrNull { it.optBoolean("primary") } ?: fs.firstOrNull() } ?: return@mapNotNull null
            PackVersion(
                v.getString("id"), v.optString("name"), v.optString("version_number"),
                v.optJSONArray("game_versions").strings(), v.optJSONArray("loaders").strings(),
                file.getString("url"), file.optJSONObject("hashes")?.optString("sha1"),
                v.optString("date_published"),
            )
        }
    }

    // Download the pack, resolve its game and loader, fetch every mod, copy overrides
    fun install(ctx: Context, p: Jobs.Progress, pack: Pack, version: PackVersion) {
        p.step("Скачиваю сборку…")
        val mrpack = ctx.cacheDir.resolve("mrpack/${version.id}.mrpack")
        if (!mrpack.isFile) Net.download(version.fileUrl, mrpack, version.fileSha1)
        ZipFile(mrpack).use { zip ->
            val index = JSONObject(zip.getInputStream(zip.getEntry("modrinth.index.json") ?: throw IOException("В архиве нет modrinth.index.json")).bufferedReader().readText())
            val deps = index.getJSONObject("dependencies")
            val mc = deps.getString("minecraft")
            val (kind, loaderVersion) = when {
                deps.has("fabric-loader") -> LoaderKind.FABRIC to deps.getString("fabric-loader")
                deps.has("quilt-loader") -> LoaderKind.QUILT to deps.getString("quilt-loader")
                deps.has("neoforge") -> LoaderKind.NEOFORGE to deps.getString("neoforge")
                deps.has("forge") -> LoaderKind.FORGE to deps.getString("forge")
                else -> LoaderKind.VANILLA to null
            }
            val mcMeta = Meta.index(ctx, "net.minecraft").firstOrNull { it.version == mc }
                ?: throw IOException("Minecraft $mc не найден в метаданных")
            Compat.problem(ctx, mcMeta)?.let { throw IOException("Minecraft $mc: $it") }

            val files = index.getJSONArray("files")
            Recipes.install(ctx, p, Recipes.components(mc, kind, loaderVersion), pack.title, "Modrinth") { game ->
                val mods = (0 until files.length()).map(files::getJSONObject).mapNotNull { f ->
                    // server-only files stay out
                    if (f.optJSONObject("env")?.optString("client") == "unsupported") return@mapNotNull null
                    val dst = safe(game, f.getString("path")) ?: return@mapNotNull null
                    val url = f.getJSONArray("downloads").getString(0)
                    Download(url, dst, f.optJSONObject("hashes")?.optString("sha1"), f.optLong("fileSize", -1))
                }
                p.fetch("Моды сборки", mods)
                p.step("Настройки сборки…")
                for (prefix in listOf("overrides/", "client-overrides/")) {
                    for (e in zip.entries()) {
                        if (e.isDirectory || !e.name.startsWith(prefix)) continue
                        val dst = safe(game, e.name.removePrefix(prefix)) ?: continue
                        dst.parentFile?.mkdirs()
                        zip.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                    }
                }
                pack.iconUrl?.let { runCatching { Net.download(it, game.resolve("icon.png")) } }
                PhoneIncompatible.disable(game.resolve("mods"))
            }
        }
        mrpack.delete()
    }

    // Paths from the pack must stay inside the game folder
    private fun safe(root: File, rel: String): File? {
        val f = root.resolve(rel).canonicalFile
        return f.takeIf { it.path.startsWith(root.canonicalPath + File.separator) }
    }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
