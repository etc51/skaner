package ru.skaner.core

/** Картинка в памяти: пиксели ARGB построчно, как в Android Bitmap.getPixels. */
class Raster(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(pixels.size == width * height) { "pixels.size != width * height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }
}

internal fun luma(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 77 + g * 150 + b * 29) shr 8
}

internal fun rgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/** Серое изображение, уменьшенное усреднением так, чтобы длинная сторона была не больше [maxSide]. */
internal class Gray(val width: Int, val height: Int, val v: FloatArray) {
    operator fun get(x: Int, y: Int): Float = v[y * width + x]

    companion object {
        fun from(src: Raster, maxSide: Int): Gray {
            val step = maxOf(1, (maxOf(src.width, src.height) + maxSide - 1) / maxSide)
            val w = maxOf(1, src.width / step)
            val h = maxOf(1, src.height / step)
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0
                val x0 = x * step
                val y0 = y * step
                for (dy in 0 until step) {
                    val row = (y0 + dy) * src.width
                    for (dx in 0 until step) sum += luma(src.pixels[row + x0 + dx])
                }
                out[y * w + x] = sum.toFloat() / (step * step)
            }
            return Gray(w, h, out)
        }
    }
}
