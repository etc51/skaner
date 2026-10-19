package ru.skaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuadTest {

    @Test
    fun ordersCornersClockwise() {
        val q = Quad.of(listOf(Point(90f, 110f), Point(10f, 5f), Point(5f, 100f), Point(100f, 0f)))
        assertEquals(Point(10f, 5f), q.topLeft)
        assertEquals(Point(100f, 0f), q.topRight)
        assertEquals(Point(90f, 110f), q.bottomRight)
        assertEquals(Point(5f, 100f), q.bottomLeft)
        assertTrue(q.isConvex())
    }

    @Test
    fun detectsNonConvex() {
        val q = Quad(Point(0f, 0f), Point(100f, 0f), Point(20f, 20f), Point(0f, 100f))
        assertFalse(q.isConvex())
    }

    @Test
    fun outputSizeIsCapped() {
        val q = Quad.full(2000f, 4000f)
        assertEquals(1000 to 2000, q.outputSize(2000))
        assertEquals(2000 to 4000, q.outputSize(5000))
    }
}
