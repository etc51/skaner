package ru.skaner.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import ru.skaner.core.EdgeDetector
import ru.skaner.core.Filter
import ru.skaner.core.Filters
import ru.skaner.core.JpegPage
import ru.skaner.core.PdfWriter
import ru.skaner.core.Quad
import ru.skaner.core.Raster
import ru.skaner.data.Doc
import ru.skaner.data.Docs
import ru.skaner.data.Page
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Вся работа с картинками: импорт снимка, поиск листа, выравнивание, фильтр, экспорт. Вызывать не из UI-потока. */
object Scanner {

    /** Длинная сторона хранимого снимка: хватает для печати A4 и не съедает память. */
    private const val SOURCE_SIDE = 3000

    /** Длинная сторона готовой страницы, около 300 dpi для A4. */
    private const val PAGE_SIDE = 3000

    private const val DETECT_SIDE = 1000

    /** Читает снимок, поворачивает по EXIF, уменьшает и сохраняет как исходник новой страницы. */
    fun importPage(context: Context, uri: Uri, docId: String): Page {
        val docs = Docs.get(context)
        val pageId = Docs.newId()
        val bitmap = decode(context, uri, SOURCE_SIDE) ?: error("Не удалось открыть изображение")
        val src = docs.sourceFile(docId, pageId).apply { parentFile?.mkdirs() }
        src.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val quad = detect(bitmap)
        bitmap.recycle()
        return Page(pageId, quad)
    }

    /** Углы листа на исходнике страницы, null — если не нашли. */
    fun detect(context: Context, docId: String, page: Page): Quad? {
        val bitmap = decodeFile(Docs.get(context).sourceFile(docId, page.id), DETECT_SIDE) ?: return null
        return detect(bitmap).also { bitmap.recycle() }
    }

    private fun detect(full: Bitmap): Quad? {
        val k = DETECT_SIDE.toFloat() / max(full.width, full.height)
        val small = if (k < 1f) Bitmap.createScaledBitmap(full, (full.width * k).roundToInt(), (full.height * k).roundToInt(), true) else full
        val raster = Raster(small.width, small.height).also { small.getPixels(it.pixels, 0, small.width, 0, 0, small.width, small.height) }
        val quad = EdgeDetector.detect(raster)?.scaled(full.width.toFloat() / small.width, full.height.toFloat() / small.height)
        if (small !== full) small.recycle()
        return quad
    }

    /** Размер исходника страницы без чтения пикселей. */
    fun sourceSize(context: Context, docId: String, page: Page): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(Docs.get(context).sourceFile(docId, page.id).path, o)
        return o.outWidth to o.outHeight
    }

    /** Выравнивает лист по углам, поворачивает, применяет фильтр и сохраняет готовую страницу. */
    fun render(context: Context, docId: String, page: Page) {
        val docs = Docs.get(context)
        val src = decodeFile(docs.sourceFile(docId, page.id), SOURCE_SIDE) ?: return
        val (w, h) = sourceSize(context, docId, page)
        val flat = warp(src, page.quad?.scaled(src.width.toFloat() / w, src.height.toFloat() / h), PAGE_SIDE)
        src.recycle()
        val result = applyFilter(rotate(flat, page.rotation), page.filter)
        val out = docs.pageFile(docId, page.id)
        val tmp = File(out.parentFile, "${page.id}.jpg.part")
        tmp.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        tmp.renameTo(out)
        result.recycle()
        Thumbs.forget(out)
        docs.touch()
    }

    /** Маленькая выровненная страница без фильтра, для миниатюр фильтров. */
    fun preview(context: Context, docId: String, page: Page, maxSide: Int): Bitmap? {
        val src = decodeFile(Docs.get(context).sourceFile(docId, page.id), maxSide * 4) ?: return null
        val (w, h) = sourceSize(context, docId, page)
        val flat = warp(src, page.quad?.scaled(src.width.toFloat() / w, src.height.toFloat() / h), maxSide)
        src.recycle()
        return rotate(flat, page.rotation)
    }

    /** Перспективное выравнивание: четырёхугольник листа растягивается в прямоугольник. */
    private fun warp(src: Bitmap, quad: Quad?, maxSide: Int): Bitmap {
        val q = quad ?: Quad.full(src.width.toFloat(), src.height.toFloat())
        val (w, h) = q.outputSize(maxSide)
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val from = q.points.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val to = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val matrix = Matrix().apply { setPolyToPoly(from, 0, to, 0, 4) }
        Canvas(flat).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return flat
    }

    /** Применяет фильтр к копии картинки (для превью фильтров) или к самой картинке. */
    fun applyFilter(bitmap: Bitmap, filter: Filter): Bitmap {
        if (filter == Filter.ORIGINAL) return bitmap
        val raster = Raster(bitmap.width, bitmap.height)
        bitmap.getPixels(raster.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val out = Filters.apply(raster, filter)
        val result = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(out.pixels, 0, out.width, 0, 0, out.width, out.height)
        return result
    }

    private fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (r !== b) b.recycle()
        return r
    }

    /** Собирает PDF из готовых страниц во временную папку для «Поделиться». */
    fun exportPdf(context: Context, doc: Doc): File {
        val docs = Docs.get(context)
        val out = File(exportDir(context), "${fileName(doc.name)}.pdf")
        val pages = doc.pages.map { p ->
            val f = docs.pageFile(doc.id, p.id)
            if (!f.exists()) render(context, doc.id, p)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            JpegPage(f.readBytes(), o.outWidth, o.outHeight)
        }
        out.outputStream().buffered().use { PdfWriter.write(pages, it) }
        return out
    }

    /** Копирует готовые страницы в JPG с понятными именами. */
    fun exportJpgs(context: Context, doc: Doc): List<File> {
        val docs = Docs.get(context)
        val base = fileName(doc.name)
        val dir = exportDir(context)
        return doc.pages.mapIndexed { i, p ->
            val f = docs.pageFile(doc.id, p.id)
            if (!f.exists()) render(context, doc.id, p)
            val name = if (doc.pages.size == 1) "$base.jpg" else "$base-${i + 1}.jpg"
            f.copyTo(File(dir, name), overwrite = true)
        }
    }

    private fun exportDir(context: Context): File =
        File(context.cacheDir, "export").apply {
            deleteRecursively()
            mkdirs()
        }

    /** Имя файла без символов, которые не любят Windows и мессенджеры. */
    fun fileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "Документ" }

    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // С inJustDecodeBounds декодер всегда возвращает null, размер приходит в bounds.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, sampled(bounds, maxSide))
        } ?: return null
        return fit(rotate(bitmap, orientation), maxSide)
    }

    fun decodeFile(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val bitmap = BitmapFactory.decodeFile(file.path, sampled(bounds, maxSide)) ?: return null
        return fit(bitmap, maxSide)
    }

    private fun sampled(bounds: BitmapFactory.Options, maxSide: Int) = BitmapFactory.Options().apply {
        var s = 1
        while (max(bounds.outWidth, bounds.outHeight) / (s * 2) >= maxSide) s *= 2
        inSampleSize = s
        inMutable = true
    }

    private fun fit(b: Bitmap, maxSide: Int): Bitmap {
        val k = maxSide.toFloat() / max(b.width, b.height)
        if (k >= 1f) return b
        val r = Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
        if (r !== b) b.recycle()
        return r
    }

    private fun exifRotation(input: InputStream): Int = try {
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}

/** Уменьшенные копии страниц для списков, в памяти. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(file: File, maxSide: Int): Bitmap? {
        val key = "${file.path}:${file.lastModified()}:$maxSide"
        cache.get(key)?.let { return it }
        val b = Scanner.decodeFile(file, maxSide) ?: return null
        cache.put(key, b)
        return b
    }

    fun forget(file: File) {
        cache.snapshot().keys.filter { it.startsWith(file.path + ":") }.forEach { cache.remove(it) }
    }
}
