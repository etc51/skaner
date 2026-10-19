package ru.skaner.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import ru.skaner.core.Filter
import ru.skaner.data.Doc
import ru.skaner.data.Docs
import ru.skaner.scan.Scanner
import java.io.File

/**
 * Демо-документы для скриншотов (только debug-сборка), из «фото» в assets/demo:
 * adb shell am broadcast -n <пакет>/ru.skaner.debug.DemoDataReceiver -a ru.skaner.debug.SEED
 * Действие NEW кладёт свежий снимок без выравнивания, его углы открываются экраном crop:new:<страница>:new.
 */
class DemoDataReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    "ru.skaner.debug.SEED" -> seed(context)
                    "ru.skaner.debug.NEW" -> addNew(context)
                    "ru.skaner.debug.CLEAR" -> clear(context)
                    // Запасной путь для автопроверки, если системное окно выбора фото не удалось пройти.
                    "ru.skaner.debug.IMPORT" -> importFile(context, intent.getStringExtra("path")!!)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun clear(context: Context) {
        val docs = Docs.get(context)
        docs.list().forEach { docs.delete(it.id) }
    }

    private fun seed(context: Context) {
        clear(context)
        val docs = Docs.get(context)
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        make(context, "demo1", "Договор аренды квартиры", now, listOf(0, 1, 2), Filter.ENHANCE)
        make(context, "demo2", "Справка с работы", now - 26 * hour, listOf(2), Filter.ENHANCE)
        make(context, "demo3", "Гарантийный талон", now - 70 * hour, listOf(3), Filter.BW)
        make(context, "demo4", "Акт приёма-передачи", now - 120 * hour, listOf(1, 3), Filter.GRAY)
        docs.touch()
    }

    private fun addNew(context: Context) {
        val docs = Docs.get(context)
        docs.delete("new")
        val page = Scanner.importPage(context, demoUri(context, 1), "new")
        val now = System.currentTimeMillis()
        docs.save(Doc("new", "Новый скан", now, now, listOf(page)))
    }

    private fun importFile(context: Context, path: String) {
        val docs = Docs.get(context)
        val f = File(context.cacheDir, "import.jpg")
        val src = if (path.startsWith("/")) File(path) else File(context.filesDir.parentFile, path)
        src.copyTo(f, overwrite = true)
        val page = Scanner.importPage(context, Uri.fromFile(f), "imported")
        Scanner.render(context, "imported", page)
        val now = System.currentTimeMillis()
        docs.save(Doc("imported", "Imported", now, now, listOf(page)))
        Scanner.render(context, "imported", page)
    }

    private fun make(context: Context, id: String, name: String, time: Long, photos: List<Int>, filter: Filter) {
        val docs = Docs.get(context)
        val pages = photos.map { Scanner.importPage(context, demoUri(context, it), id).copy(filter = filter) }
        docs.save(Doc(id, name, time, time, pages))
        pages.forEach { Scanner.render(context, id, it) }
    }

    private fun demoUri(context: Context, i: Int): Uri {
        val f = File(context.cacheDir, "demo$i.jpg")
        context.assets.open("demo/photo$i.jpg").use { input -> f.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(f)
    }
}
