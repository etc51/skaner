package ru.skaner.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ищет на фото края листа без OpenCV и без сети.
 *
 * Схема: уменьшаем кадр, размываем, считаем перепады яркости (Собель) и оставляем
 * только тонкие гребни. По ним преобразованием Хафа находим прямые, делим их
 * на «почти горизонтальные» и «почти вертикальные» и перебираем четвёрки.
 * Побеждает выпуклый четырёхугольник, чьи стороны лучше всего лежат на найденных краях.
 */
object EdgeDetector {

    private const val WORK_SIDE = 400
    private const val THETA_BINS = 180
    private const val MAX_LINES_PER_GROUP = 10

    /** Возвращает углы листа в координатах [src] или null, если уверенно найти лист не получилось. */
    fun detect(src: Raster): Quad? {
        val gray = Gray.from(src, WORK_SIDE)
        val scale = src.width.toFloat() / gray.width
        val w = gray.width
        val h = gray.height
        if (w < 32 || h < 32) return null

        val blurred = blur(close(gray, 3))
        val edges = edgeMap(blurred) ?: return null
        val lines = houghLines(edges, w, h)
        // Рамка кадра тоже кандидат: лист мог в кадр не поместиться.
        val horizontal = lines.filter { it.theta in (PI / 4)..(3 * PI / 4) }.take(MAX_LINES_PER_GROUP) +
            listOf(Line(PI / 2, 0.5, 0), Line(PI / 2, h - 1.5, 0))
        val vertical = lines.filter { it.theta !in (PI / 4)..(3 * PI / 4) }.take(MAX_LINES_PER_GROUP) +
            listOf(Line(0.0, 0.5, 0), Line(0.0, w - 1.5, 0))
        if (horizontal.size < 2 || vertical.size < 2) return null

        val support = dilate(edges.strength, w, h)
        var best: Quad? = null
        var bestScore = 0f
        val minArea = 0.12f * w * h
        for (i in horizontal.indices) for (j in i + 1 until horizontal.size) {
            for (k in vertical.indices) for (l in k + 1 until vertical.size) {
                val quad = quadFrom(horizontal[i], horizontal[j], vertical[k], vertical[l], w, h) ?: continue
                val area = quad.area()
                if (area < minArea || !quad.isConvex()) continue
                val quality = quality(quad, support, w, h) ?: continue
                // Из похожих по качеству рамок берём бóльшую: строки текста и таблицы
                // внутри листа тоже дают ровные прямые, но лист всегда снаружи.
                val score = quality * sqrt(area)
                if (score > bestScore) {
                    bestScore = score
                    best = quad
                }
            }
        }
        val found = best ?: return null
        return found.scaled(scale, src.height.toFloat() / gray.height).clamped(src.width.toFloat(), src.height.toFloat())
    }

    internal class Edges(val mask: BooleanArray, val angle: FloatArray, val strength: FloatArray)

    internal data class Line(val theta: Double, val rho: Double, val votes: Int)

    /**
     * Морфологическое закрытие: максимум, затем минимум по окну. Убирает тёмные
     * штрихи тоньше окна (строки текста), а граница листа и фона остаётся на месте.
     */
    private fun close(g: Gray, r: Int): Gray = Gray(g.width, g.height, extreme(extreme(g.v, g.width, g.height, r, true), g.width, g.height, r, false))

    private fun extreme(src: FloatArray, w: Int, h: Int, r: Int, max: Boolean): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = src[y * w + x]
            for (i in maxOf(0, x - r)..minOf(w - 1, x + r)) {
                val v = src[y * w + i]
                if (if (max) v > m else v < m) m = v
            }
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = tmp[y * w + x]
            for (i in maxOf(0, y - r)..minOf(h - 1, y + r)) {
                val v = tmp[i * w + x]
                if (if (max) v > m else v < m) m = v
            }
            out[y * w + x] = m
        }
        return out
    }

    private fun blur(g: Gray): Gray {
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val w = g.width
        val h = g.height
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -2..2) s += k[i + 2] * g.v[y * w + (x + i).coerceIn(0, w - 1)]
            tmp[y * w + x] = s / 16f
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -2..2) s += k[i + 2] * tmp[(y + i).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = s / 16f
        }
        return Gray(w, h, out)
    }

    /** Собель и прореживание: остаются только локальные максимумы перепада поперёк края. */
    private fun edgeMap(g: Gray): Edges? {
        val w = g.width
        val h = g.height
        val mag = FloatArray(w * h)
        val ang = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = (g[x + 1, y - 1] + 2 * g[x + 1, y] + g[x + 1, y + 1]) - (g[x - 1, y - 1] + 2 * g[x - 1, y] + g[x - 1, y + 1])
            val gy = (g[x - 1, y + 1] + 2 * g[x, y + 1] + g[x + 1, y + 1]) - (g[x - 1, y - 1] + 2 * g[x, y - 1] + g[x + 1, y - 1])
            mag[y * w + x] = hypot(gx, gy)
            ang[y * w + x] = atan2(gy, gx)
        }
        val sorted = mag.copyOf().also { it.sort() }
        val maxMag = sorted.last()
        if (maxMag < 20f) return null
        // Порог выше шума (медианы перепадов), но низкий: край листа в тени слабее, чем у текста.
        val threshold = max(sorted[sorted.size / 2] * 3f, maxMag * 0.06f)
        val mask = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m < threshold) continue
            val a = ang[i]
            val dx = cos(a).roundToInt()
            val dy = sin(a).roundToInt()
            if (m >= mag[(y + dy) * w + (x + dx)] && m >= mag[(y - dy) * w + (x - dx)]) mask[i] = true
        }
        // Сила края от 0 до 1: резкий край листа весомее, чем мягкие полосы на столе.
        val strong = max(sorted[(sorted.size * 0.995).toInt()], 1f)
        val strength = FloatArray(w * h) { if (mask[it]) min(1f, mag[it] / strong) else 0f }
        return Edges(mask, ang, strength)
    }

    /** Голосуют только направления, близкие к нормали края: меньше шума и быстрее. */
    private fun houghLines(edges: Edges, w: Int, h: Int): List<Line> {
        val diag = sqrt((w * w + h * h).toDouble()).toInt() + 1
        val rhoBins = 2 * diag + 1
        val acc = IntArray(THETA_BINS * rhoBins)
        val cosT = DoubleArray(THETA_BINS) { cos(it * PI / THETA_BINS) }
        val sinT = DoubleArray(THETA_BINS) { sin(it * PI / THETA_BINS) }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!edges.mask[i]) continue
            var t = edges.angle[i].toDouble()
            if (t < 0) t += PI
            val center = ((t / PI) * THETA_BINS).roundToInt()
            for (d in -6..6) {
                val ti = Math.floorMod(center + d, THETA_BINS)
                val rho = (x * cosT[ti] + y * sinT[ti]).roundToInt() + diag
                acc[ti * rhoBins + rho]++
            }
        }

        val minVotes = (min(w, h) * 0.12).toInt()
        val peaks = ArrayList<Line>()
        for (ti in 0 until THETA_BINS) for (r in 0 until rhoBins) {
            val v = acc[ti * rhoBins + r]
            if (v < minVotes) continue
            var isMax = true
            loop@ for (dt in -2..2) for (dr in -3..3) {
                if (dt == 0 && dr == 0) continue
                val tt = Math.floorMod(ti + dt, THETA_BINS)
                // При переходе через 180° прямая та же, а знак расстояния меняется.
                val rr = if (ti + dt < 0 || ti + dt >= THETA_BINS) 2 * diag - (r + dr) else r + dr
                if (rr < 0 || rr >= rhoBins) continue
                val o = acc[tt * rhoBins + rr]
                if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) {
                    isMax = false
                    break@loop
                }
            }
            if (isMax) peaks += Line(ti * PI / THETA_BINS, (r - diag).toDouble(), v)
        }
        peaks.sortByDescending { it.votes }

        // Убираем почти совпадающие прямые.
        val result = ArrayList<Line>()
        for (p in peaks) {
            val dup = result.any { q ->
                val dTheta = abs(p.theta - q.theta)
                (dTheta < 0.14 && abs(p.rho - q.rho) < 12) || (PI - dTheta < 0.14 && abs(p.rho + q.rho) < 12)
            }
            if (!dup) result += p
            if (result.size >= 40) break
        }
        return result
    }

    private fun intersect(a: Line, b: Line): Point? {
        val c1 = cos(a.theta)
        val s1 = sin(a.theta)
        val c2 = cos(b.theta)
        val s2 = sin(b.theta)
        val det = c1 * s2 - s1 * c2
        if (abs(det) < 1e-6) return null
        val x = (a.rho * s2 - b.rho * s1) / det
        val y = (c1 * b.rho - c2 * a.rho) / det
        return Point(x.toFloat(), y.toFloat())
    }

    private fun quadFrom(h1: Line, h2: Line, v1: Line, v2: Line, w: Int, h: Int): Quad? {
        val pts = listOf(intersect(h1, v1), intersect(h1, v2), intersect(h2, v1), intersect(h2, v2))
        if (pts.any { it == null }) return null
        val margin = 0.04f * max(w, h)
        if (pts.any { it!!.x < -margin || it.y < -margin || it.x > w + margin || it.y > h + margin }) return null
        return Quad.of(pts.map { it!! })
    }

    private fun dilate(v: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) m = max(m, v[yy * w + xx])
            }
            out[y * w + x] = m
        }
        return out
    }

    /**
     * Насколько стороны лежат на сильных краях, от 0 до 1. Сторона у самой границы
     * кадра засчитывается частично: лист мог в кадр не поместиться.
     */
    private fun quality(q: Quad, support: FloatArray, w: Int, h: Int): Float? {
        val p = q.points
        var total = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val len = a.distanceTo(b)
            if (len < 0.1f * min(w, h)) return null
            val n = len.toInt().coerceAtLeast(1)
            var hits = 0
            var strength = 0f
            var border = 0
            for (s in 0..n) {
                val x = (a.x + (b.x - a.x) * s / n).roundToInt()
                val y = (a.y + (b.y - a.y) * s / n).roundToInt()
                if (x <= 2 || y <= 2 || x >= w - 3 || y >= h - 3) {
                    border++
                } else if (support[y * w + x] > 0f) {
                    hits++
                    strength += support[y * w + x]
                }
            }
            val fraction = (hits + border).toFloat() / (n + 1)
            if (fraction < 0.4f) return null
            total += (strength + border * 0.25f) / (n + 1)
        }
        return total / 4
    }
}
