package ru.skaner.core

import kotlin.test.Test
import kotlin.test.assertTrue

class FiltersTest {

    private val page = Synthetic.photo(600, 800, Quad.full(600f, 800f), seed = 5, shadow = true)

    @Test
    fun whitenEvensOutShadow() {
        val out = Filters.whiten(page)
        // Пустая бумага у тёмного правого края должна стать почти белой.
        val right = luma(out[590, 40])
        val left = luma(out[10, 40])
        assertTrue(right > 225, "справа $right")
        assertTrue(left > 225, "слева $left")
        // А текст остаться тёмным.
        assertTrue(luma(out[300, 130]) < 120, "текст ${luma(out[300, 130])}")
    }

    @Test
    fun bwIsOnlyBlackAndWhite() {
        val out = Filters.threshold(page)
        assertTrue(out.pixels.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() })
        assertTrue(out[590, 40] == 0xFFFFFFFF.toInt())
    }
}
