package ru.skaner.core

import kotlin.random.Random

/** Рисует «фото» листа с текстом на тёмном столе для тестов. */
object Synthetic {

    fun photo(width: Int, height: Int, sheet: Quad, seed: Int = 1, shadow: Boolean = false): Raster {
        val rnd = Random(seed)
        val r = Raster(width, height)
        val p = sheet.points
        for (y in 0 until height) for (x in 0 until width) {
            val inside = inside(p, x + 0.5f, y + 0.5f)
            val noise = rnd.nextInt(-12, 13)
            val v = if (inside) {
                // Строки «текста» внутри листа, в координатах примерно от левого верхнего угла.
                val u = (x - sheet.topLeft.x) / (sheet.topRight.x - sheet.topLeft.x)
                val t = (y - sheet.topLeft.y) / (sheet.bottomLeft.y - sheet.topLeft.y)
                val text = u in 0.12f..0.88f && t in 0.1f..0.9f && ((t * 40).toInt() % 3 == 0) && ((u * 60).toInt() % 7 != 0)
                val base = if (shadow) (230 - 90 * u).toInt() else 225
                if (text) 50 else base
            } else {
                70 + ((x / 23 + y / 17) % 3) * 6
            }
            val l = (v + noise).coerceIn(0, 255)
            r[x, y] = rgb(l, l, (l * 0.95).toInt())
        }
        return r
    }

    private fun inside(p: List<Point>, x: Float, y: Float): Boolean {
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < 0) return false
        }
        return true
    }
}
