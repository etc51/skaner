package ru.skaner.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

data class Point(val x: Float, val y: Float) {
    fun distanceTo(o: Point): Float = hypot(x - o.x, y - o.y)
}

/** Углы листа по часовой стрелке, начиная с левого верхнего. */
data class Quad(val topLeft: Point, val topRight: Point, val bottomRight: Point, val bottomLeft: Point) {

    val points: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun scaled(sx: Float, sy: Float = sx): Quad = Quad(
        Point(topLeft.x * sx, topLeft.y * sy),
        Point(topRight.x * sx, topRight.y * sy),
        Point(bottomRight.x * sx, bottomRight.y * sy),
        Point(bottomLeft.x * sx, bottomLeft.y * sy),
    )

    fun clamped(width: Float, height: Float): Quad = Quad.of(points.map { Point(it.x.coerceIn(0f, width), it.y.coerceIn(0f, height)) })

    /** Площадь по формуле шнурования. */
    fun area(): Float {
        val p = points
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** Выпуклый ли четырёхугольник: все повороты в одну сторону. */
    fun isConvex(): Boolean {
        val p = points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /**
     * Размер выровненной страницы в пикселях: по длинам сторон,
     * но не больше [maxSide] по длинной стороне.
     */
    fun outputSize(maxSide: Int): Pair<Int, Int> {
        val w = max(topLeft.distanceTo(topRight), bottomLeft.distanceTo(bottomRight))
        val h = max(topLeft.distanceTo(bottomLeft), topRight.distanceTo(bottomRight))
        val k = minOf(1f, maxSide / max(w, h))
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }

    companion object {
        /** Весь кадр с небольшим отступом от краёв. */
        fun full(width: Float, height: Float, inset: Float = 0f): Quad = Quad(
            Point(inset, inset),
            Point(width - inset, inset),
            Point(width - inset, height - inset),
            Point(inset, height - inset),
        )

        /** Раскладывает четыре точки в любом порядке по углам. */
        fun of(p: List<Point>): Quad {
            require(p.size == 4)
            val cx = p.sumOf { it.x.toDouble() }.toFloat() / 4
            val cy = p.sumOf { it.y.toDouble() }.toFloat() / 4
            // Сортируем по углу вокруг центра: так порядок верный и для сильно повёрнутого листа.
            val sorted = p.sortedBy { kotlin.math.atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
            // После сортировки обход идёт по часовой стрелке (ось y вниз). Начинаем с точки с наименьшей x+y.
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val r = List(4) { sorted[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }
    }
}
