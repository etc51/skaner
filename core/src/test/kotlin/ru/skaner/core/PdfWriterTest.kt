package ru.skaner.core

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfWriterTest {

    @Test
    fun xrefPointsAtObjects() {
        val pages = listOf(
            JpegPage(byteArrayOf(1, 2, 3, 4), 100, 141),
            JpegPage(byteArrayOf(5, 6, 7), 300, 200),
        )
        val bytes = ByteArrayOutputStream().also { PdfWriter.write(pages, it) }.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))

        val startxref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref", startxref))
        val entries = text.substring(startxref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(2 + 3 * pages.size, entries.size)
        for ((i, e) in entries.withIndex()) {
            val offset = e.substring(0, 10).toInt()
            assertTrue(text.startsWith("${i + 1} 0 obj", offset), "объект ${i + 1} не по адресу $offset")
        }
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/Width 300 /Height 200"))
    }

    @Test
    fun pageKeepsProportions() {
        val (w, h) = PdfWriter.pageSize(JpegPage(ByteArray(0), 1000, 1414))
        assertEquals(595.0, w)
        assertEquals(841.4, h, 0.1)
        val (lw, lh) = PdfWriter.pageSize(JpegPage(ByteArray(0), 2000, 1000))
        assertEquals(1190.0, lw)
        assertEquals(595.0, lh)
    }
}
