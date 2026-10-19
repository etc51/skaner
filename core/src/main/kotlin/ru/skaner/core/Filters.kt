package ru.skaner.core

import kotlin.math.pow

/** Обработка выровненной страницы. */
enum class Filter(val title: String) {
    ORIGINAL("Оригинал"),
    ENHANCE("Улучшить"),
    GRAY("Серый"),
    BW("Ч/Б");

    companion object {
        fun byName(name: String?): Filter = entries.firstOrNull { it.name == name } ?: ENHANCE
    }
}

object Filters {

    fun apply(src: Raster, filter: Filter): Raster = when (filter) {
        Filter.ORIGINAL -> src
        Filter.ENHANCE -> whiten(src)
        Filter.GRAY -> toGray(whiten(src))
        Filter.BW -> threshold(src)
    }

    /**
     * Выравнивает освещение: оцениваем яркость бумаги в каждой области
     * и растягиваем её до белого. Тени и желтизна пропадают, цвет печатей и ручки остаётся.
     */
    fun whiten(src: Raster): Raster {
        val bg = paperBrightness(src)
        val out = IntArray(src.pixels.size)
        val w = src.width
        for (y in 0 until src.height) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.pixels[i]
                val k = 255f / bg.at(x, y).coerceAtLeast(40f)
                val r = curve(((c shr 16) and 0xFF) * k)
                val g = curve(((c shr 8) and 0xFF) * k)
                val b = curve((c and 0xFF) * k)
                out[i] = rgb(r, g, b)
            }
        }
        return Raster(w, src.height, out)
    }

    fun toGray(src: Raster): Raster {
        val out = IntArray(src.pixels.size) { val l = luma(src.pixels[it]); rgb(l, l, l) }
        return Raster(src.width, src.height, out)
    }

    /**
     * Чёрно-белый режим: пиксель чёрный, если он заметно темнее среднего по окрестности
     * (адаптивный порог Брэдли по интегральному изображению).
     */
    fun threshold(src: Raster, sensitivity: Float = 0.15f): Raster {
        val w = src.width
        val h = src.height
        val integral = LongArray((w + 1) * (h + 1))
        val lum = IntArray(w * h) { luma(src.pixels[it]) }
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += lum[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val half = maxOf(4, maxOf(w, h) / 32)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - half)
            val y1 = minOf(h - 1, y + half)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - half)
                val x1 = minOf(w - 1, x + half)
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (w + 1) + x1 + 1] - integral[y0 * (w + 1) + x1 + 1] -
                    integral[(y1 + 1) * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val black = lum[y * w + x] * count < sum * (1f - sensitivity)
                out[y * w + x] = if (black) BLACK else WHITE
            }
        }
        return Raster(w, h, out)
    }

    /** Светлое уходит в белый, средние тона темнеют, чтобы текст стал плотнее. */
    private fun curve(v: Float): Int {
        val x = (v / 255f).coerceIn(0f, 1f)
        val y = if (x >= 0.88f) 1f else (x / 0.88f).pow(1.3f)
        return (y * 255f).toInt()
    }

    private class Grid(val cols: Int, val rows: Int, val cell: Float, val v: FloatArray) {
        /** Билинейная интерполяция между центрами ячеек. */
        fun at(x: Int, y: Int): Float {
            val gx = (x / cell - 0.5f).coerceIn(0f, cols - 1f)
            val gy = (y / cell - 0.5f).coerceIn(0f, rows - 1f)
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val x1 = minOf(x0 + 1, cols - 1)
            val y1 = minOf(y0 + 1, rows - 1)
            val fx = gx - x0
            val fy = gy - y0
            val top = v[y0 * cols + x0] * (1 - fx) + v[y0 * cols + x1] * fx
            val bottom = v[y1 * cols + x0] * (1 - fx) + v[y1 * cols + x1] * fx
            return top * (1 - fy) + bottom * fy
        }
    }

    /** Яркость бумаги по ячейкам: светлый перцентиль, затем максимум и сглаживание по соседям. */
    private fun paperBrightness(src: Raster): Grid {
        val cell = maxOf(8f, maxOf(src.width, src.height) / 40f)
        val cols = maxOf(1, kotlin.math.ceil(src.width / cell).toInt())
        val rows = maxOf(1, kotlin.math.ceil(src.height / cell).toInt())
        val raw = FloatArray(cols * rows)
        val hist = IntArray(256)
        val step = maxOf(1, (cell / 12).toInt())
        for (cy in 0 until rows) for (cx in 0 until cols) {
            hist.fill(0)
            var n = 0
            val x0 = (cx * cell).toInt()
            val y0 = (cy * cell).toInt()
            val x1 = minOf(src.width, ((cx + 1) * cell).toInt())
            val y1 = minOf(src.height, ((cy + 1) * cell).toInt())
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    hist[luma(src.pixels[y * src.width + x])]++
                    n++
                    x += step
                }
                y += step
            }
            raw[cy * cols + cx] = if (n == 0) 255f else percentile(hist, n, 0.9f)
        }
        // Ячейка, целиком занятая картинкой или текстом, берёт яркость у соседей.
        val dilated = FloatArray(raw.size)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            var m = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until cols && y in 0 until rows) m = maxOf(m, raw[y * cols + x])
            }
            dilated[cy * cols + cx] = m
        }
        val smooth = FloatArray(raw.size)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            var s = 0f
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until cols && y in 0 until rows) {
                    s += dilated[y * cols + x]
                    n++
                }
            }
            smooth[cy * cols + cx] = s / n
        }
        return Grid(cols, rows, cell, smooth)
    }

    private fun percentile(hist: IntArray, n: Int, p: Float): Float {
        val target = (n * p).toInt()
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc > target) return i.toFloat()
        }
        return 255f
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
