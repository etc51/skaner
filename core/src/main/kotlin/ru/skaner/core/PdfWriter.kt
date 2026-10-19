package ru.skaner.core

import java.io.OutputStream

/** Страница PDF: готовый JPEG и его размер в пикселях. */
class JpegPage(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Пишет PDF, в котором каждая страница — одна JPEG-картинка как есть (DCTDecode).
 * Так файл получается в разы меньше, чем через android.graphics.pdf.PdfDocument,
 * который хранит картинки без сжатия JPEG.
 */
object PdfWriter {

    /** Ширина страницы в пунктах (A4 = 595 × 842), высота по пропорциям снимка. */
    private const val PAGE_SHORT_SIDE = 595.0

    fun write(pages: List<JpegPage>, out: OutputStream) {
        require(pages.isNotEmpty()) { "Нет страниц" }
        val w = CountingStream(out)
        val offsets = ArrayList<Long>()
        fun obj(body: () -> Unit) {
            offsets += w.count
            w.ascii("${offsets.size} 0 obj\n")
            body()
            w.ascii("\nendobj\n")
        }

        w.ascii("%PDF-1.4\n")
        w.bytes(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // Нумерация: 1 — каталог, 2 — список страниц, дальше по три объекта на страницу.
        val pageIds = pages.indices.map { 3 + it * 3 }
        obj { w.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj { w.ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>") }

        for ((i, page) in pages.withIndex()) {
            val (pw, ph) = pageSize(page)
            val pageId = pageIds[i]
            val content = "q ${fmt(pw)} 0 0 ${fmt(ph)} 0 0 cm /Im0 Do Q"
            obj {
                w.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pw)} ${fmt(ph)}] " +
                        "/Resources << /XObject << /Im0 ${pageId + 2} 0 R >> >> /Contents ${pageId + 1} 0 R >>",
                )
            }
            obj {
                w.ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream")
            }
            obj {
                w.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n",
                )
                w.bytes(page.jpeg)
                w.ascii("\nendstream")
            }
        }

        val xref = w.count
        w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) w.ascii(String.format("%010d 00000 n \n", o))
        w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    internal fun pageSize(p: JpegPage): Pair<Double, Double> =
        if (p.width <= p.height) {
            PAGE_SHORT_SIDE to PAGE_SHORT_SIDE * p.height / p.width
        } else {
            PAGE_SHORT_SIDE * p.width / p.height to PAGE_SHORT_SIDE
        }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)

    private class CountingStream(private val out: OutputStream) {
        var count = 0L
            private set

        fun ascii(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))

        fun bytes(b: ByteArray) {
            out.write(b)
            count += b.size
        }

        fun flush() = out.flush()
    }
}
