package ru.skaner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import ru.skaner.core.Filter
import ru.skaner.core.Point
import ru.skaner.core.Quad
import java.io.File
import java.util.UUID

/** Страница: исходный снимок, углы листа на нём, фильтр и поворот (0, 90, 180, 270). */
data class Page(
    val id: String,
    val quad: Quad?,
    val filter: Filter = Filter.ENHANCE,
    val rotation: Int = 0,
)

data class Doc(
    val id: String,
    val name: String,
    val created: Long,
    val updated: Long,
    val pages: List<Page>,
)

/**
 * Документы лежат в папке приложения: docs/<id>/doc.json, а рядом снимки страниц
 * (<page>-src.jpg) и готовые выровненные страницы (<page>.jpg). Наружу ничего не уходит.
 */
class Docs private constructor(context: Context) {

    private val root = File(context.filesDir, "docs").apply { mkdirs() }

    private val _version = MutableStateFlow(0)

    /** Растёт при каждом изменении, экраны по нему перечитывают данные. */
    val version: StateFlow<Int> = _version

    fun dir(docId: String): File = File(root, docId)

    fun sourceFile(docId: String, pageId: String) = File(dir(docId), "$pageId-src.jpg")

    fun pageFile(docId: String, pageId: String) = File(dir(docId), "$pageId.jpg")

    @Synchronized
    fun list(): List<Doc> =
        (root.listFiles() ?: emptyArray()).mapNotNull { read(it.name) }.sortedByDescending { it.updated }

    @Synchronized
    fun get(id: String): Doc? = read(id)

    @Synchronized
    fun save(doc: Doc) {
        val d = dir(doc.id).apply { mkdirs() }
        val tmp = File(d, "doc.json.tmp")
        tmp.writeText(toJson(doc).toString())
        tmp.renameTo(File(d, "doc.json"))
        // Снимки удалённых страниц больше не нужны.
        val keep = doc.pages.flatMap { listOf("${it.id}-src.jpg", "${it.id}.jpg") }.toSet() + "doc.json"
        d.listFiles()?.filter { it.name !in keep && !it.name.endsWith(".part") }?.forEach { it.delete() }
        _version.value++
    }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        _version.value++
    }

    /** Отметить, что картинки страницы перерисованы. */
    fun touch() {
        _version.value++
    }

    private fun read(id: String): Doc? = try {
        val f = File(dir(id), "doc.json")
        if (f.exists()) fromJson(JSONObject(f.readText())) else null
    } catch (e: Exception) {
        null
    }

    private fun toJson(d: Doc) = JSONObject().apply {
        put("id", d.id)
        put("name", d.name)
        put("created", d.created)
        put("updated", d.updated)
        put("pages", JSONArray().apply {
            d.pages.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("filter", p.filter.name)
                    put("rotation", p.rotation)
                    p.quad?.let { q -> put("quad", JSONArray(q.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) })) }
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Doc {
        val pages = o.getJSONArray("pages")
        return Doc(
            id = o.getString("id"),
            name = o.getString("name"),
            created = o.getLong("created"),
            updated = o.getLong("updated"),
            pages = List(pages.length()) { i ->
                val p = pages.getJSONObject(i)
                val q = p.optJSONArray("quad")
                Page(
                    id = p.getString("id"),
                    quad = q?.let { a -> Quad(pt(a, 0), pt(a, 2), pt(a, 4), pt(a, 6)) },
                    filter = Filter.byName(p.optString("filter")),
                    rotation = p.optInt("rotation"),
                )
            },
        )
    }

    private fun pt(a: JSONArray, i: Int) = Point(a.getDouble(i).toFloat(), a.getDouble(i + 1).toFloat())

    companion object {
        @Volatile
        private var instance: Docs? = null

        fun get(context: Context): Docs =
            instance ?: synchronized(this) { instance ?: Docs(context.applicationContext).also { instance = it } }

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
