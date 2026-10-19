package ru.skaner.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.skaner.data.Doc
import ru.skaner.data.Docs
import ru.skaner.scan.Scanner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Куда смотрит пользователь. Хранится строкой, чтобы пережить поворот экрана и выгрузку процесса:
 * home, doc:ID, crop:DOC:PAGE:new (только что снятая страница) или crop:DOC:PAGE:edit.
 */
class MainActivity : ComponentActivity() {

    private var route by mutableStateOf("home")

    /** Пока идёт обработка, показываем затемнение с этой подписью. */
    private var busy by mutableStateOf<String?>(null)

    /** В какой документ добавить снимок, который сейчас делается или выбирается. Пустая строка — новый документ. */
    private var target: String? = null

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) addImages(listOf(Uri.fromFile(cameraFile())))
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) addImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            route = it.getString("route") ?: "home"
            target = it.getString("target")
        }
        intent.getStringExtra(EXTRA_SCREEN)?.let { route = it }
        setContent {
            SkanerTheme {
                App(route, busy, onRoute = { route = it }, actions = actions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SCREEN)?.let { route = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("route", route)
        outState.putString("target", target)
    }

    private val actions = object : Actions {
        override fun scan(docId: String?) {
            target = docId ?: ""
            val file = cameraFile().apply {
                parentFile?.mkdirs()
                delete()
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            try {
                camera.launch(uri)
            } catch (e: ActivityNotFoundException) {
                toast("На телефоне нет приложения камеры, добавьте фото из галереи")
            }
        }

        override fun pick(docId: String?) {
            target = docId ?: ""
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        override fun work(message: String, block: suspend () -> Unit) {
            lifecycleScope.launch {
                busy = message
                try {
                    block()
                } catch (e: Exception) {
                    toast("Не получилось: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    busy = null
                }
            }
        }
    }

    /**
     * Одна картинка открывается на экране углов, чтобы сразу их поправить.
     * Несколько из галереи обрабатываются автоматически, углы можно поправить потом.
     */
    private fun addImages(uris: List<Uri>) {
        val docs = Docs.get(this)
        val docId = target?.takeIf { it.isNotEmpty() } ?: Docs.newId()
        target = null
        actions.work(if (uris.size == 1) "Ищу края листа…" else "Обрабатываю ${uris.size} фото…") {
            val doc = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val existing = docs.get(docId) ?: Doc(docId, defaultName(now), now, now, emptyList())
                val pages = uris.map { Scanner.importPage(applicationContext, it, docId) }
                existing.copy(pages = existing.pages + pages, updated = now).also { d ->
                    docs.save(d)
                    if (uris.size > 1) pages.forEach { Scanner.render(applicationContext, docId, it) }
                }
            }
            route = if (uris.size == 1) "crop:${doc.id}:${doc.pages.last().id}:new" else "doc:${doc.id}"
        }
    }

    private fun cameraFile() = File(cacheDir, "camera/shot.jpg")

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_SCREEN = "screen"
        private const val MAX_PICK = 30

        fun defaultName(time: Long): String =
            "Скан " + SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(time))
    }
}

interface Actions {
    /** Снять страницу камерой: в существующий документ или в новый (null). */
    fun scan(docId: String?)

    /** Выбрать фото из галереи. */
    fun pick(docId: String?)

    /** Долгая работа в фоне с затемнением экрана и подписью. */
    fun work(message: String, block: suspend () -> Unit)
}

@Composable
private fun App(route: String, busy: String?, onRoute: (String) -> Unit, actions: Actions) {
    val parts = route.split(":")
    AnimatedContent(
        targetState = parts.first() to parts.getOrNull(1),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { (screen, id) ->
        when {
            screen == "doc" && id != null -> {
                BackHandler { onRoute("home") }
                DocumentScreen(id, actions, onBack = { onRoute("home") }, onEditCorners = { onRoute("crop:$id:$it:edit") })
            }
            screen == "crop" && id != null && parts.size >= 4 -> {
                val isNew = parts[3] == "new"
                CropScreen(id, parts[2], isNew, actions, onDone = { onRoute("doc:$id") }, onCancel = { docLeft ->
                    onRoute(if (docLeft) "doc:$id" else "home")
                })
            }
            else -> HomeScreen(actions, onOpen = { onRoute("doc:$it") })
        }
    }
    if (busy != null) BusyOverlay(busy)
}
