package ru.skaner.core

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EdgeDetectorTest {

    private fun assertClose(expected: Quad, actual: Quad?, tolerance: Float) {
        assertNotNull(actual, "лист не найден")
        for ((e, a) in expected.points.zip(actual.points)) {
            assertTrue(e.distanceTo(a) <= tolerance, "ожидали $e, получили $a (все углы: $actual)")
        }
    }

    @Test
    fun findsSheetInPerspective() {
        val sheet = Quad(Point(180f, 120f), Point(830f, 90f), Point(900f, 1180f), Point(120f, 1150f))
        val photo = Synthetic.photo(1000, 1300, sheet)
        assertClose(sheet, EdgeDetector.detect(photo), 14f)
    }

    @Test
    fun findsRotatedSheet() {
        val sheet = Quad(Point(300f, 60f), Point(1150f, 330f), Point(900f, 880f), Point(80f, 600f))
        val photo = Synthetic.photo(1200, 950, sheet, seed = 7)
        assertClose(sheet, EdgeDetector.detect(photo), 14f)
    }

    @Test
    fun findsSheetWithShadow() {
        val sheet = Quad(Point(100f, 100f), Point(700f, 120f), Point(690f, 950f), Point(110f, 930f))
        val photo = Synthetic.photo(800, 1050, sheet, seed = 3, shadow = true)
        assertClose(sheet, EdgeDetector.detect(photo), 14f)
    }

    @Test
    fun plainImageGivesNothing() {
        val flat = Raster(600, 800).also { it.pixels.fill(rgb(128, 128, 128)) }
        assertTrue(EdgeDetector.detect(flat) == null)
    }
}
