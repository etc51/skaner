package ru.skaner.core

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Фото листов на разных фонах, с тенью и выходом листа за кадр (сгенерированы для тестов). */
class PhotoDetectionTest {

    private fun load(name: String): Raster {
        val img = ImageIO.read(javaClass.getResourceAsStream("/photos/$name.jpg"))
        val r = Raster(img.width, img.height)
        img.getRGB(0, 0, img.width, img.height, r.pixels, 0, img.width)
        return r
    }

    private fun expected(name: String, width: Float, height: Float): Quad {
        val v = javaClass.getResource("/photos/$name.txt")!!.readText().trim().split(" ").map { it.toFloat() }
        return Quad.of(List(4) { Point(v[it * 2], v[it * 2 + 1]) }).clamped(width, height)
    }

    private fun check(name: String, tolerance: Float = 20f) {
        val photo = load(name)
        val found = EdgeDetector.detect(photo)
        assertNotNull(found, "$name: лист не найден")
        val want = expected(name, photo.width.toFloat(), photo.height.toFloat())
        for ((e, a) in want.points.zip(found.points)) {
            assertTrue(e.distanceTo(a) < tolerance, "$name: ожидали $e, получили $a ($found)")
        }
    }

    @Test fun woodTable() = check("photo0")

    @Test fun rotatedOnDesk() = check("photo1")

    // Белый лист на светлом полосатом столе: край слабый, допускаем промах на одну полосу фона.
    @Test fun lightBackground() = check("photo2", tolerance = 45f)

    @Test fun sheetCutByFrame() = check("photo3")

    // Листы с настоящим текстом, печатью и таблицей, как в демо-данных для скриншотов.
    @Test fun textOnWood() = check("demo0")

    @Test fun textRotatedOnDesk() = check("demo1")

    @Test fun textStraight() = check("demo2")

    @Test fun textWithTable() = check("demo3")
}
