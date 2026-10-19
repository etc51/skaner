package ru.skaner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.skaner.data.Doc
import ru.skaner.data.Docs
import ru.skaner.scan.Thumbs
import java.io.File

/** Читает данные документов в фоне и перечитывает после каждого изменения. */
@Composable
fun <T> rememberDocs(initial: T, vararg keys: Any?, load: (Docs) -> T): T {
    val context = LocalContext.current
    val docs = remember { Docs.get(context) }
    val version by docs.version.collectAsState()
    val value by produceState(initial, version, *keys) {
        value = withContext(Dispatchers.IO) { load(docs) }
    }
    return value
}

/** Картинка из файла, уменьшенная до [maxSide], загружается в фоне. */
@Composable
fun FileImage(
    file: File?,
    maxSide: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val version by Docs.get(LocalContext.current).version.collectAsState()
    val bitmap by produceState<Bitmap?>(null, file, version, maxSide) {
        value = if (file == null) null else withContext(Dispatchers.IO) { Thumbs.get(file, maxSide) }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

fun Docs.cover(doc: Doc): File? = doc.pages.firstOrNull()?.let { pageFile(doc.id, it.id) }

@Composable
fun BusyOverlay(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // Перехватываем нажатия, пока идёт обработка.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 12.dp) {
            Column(
                Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 4.dp)
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = value.text.isNotBlank(), onClick = { onRename(value.text.trim()) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

fun pagesLabel(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "страниц"
        n % 10 == 1 -> "страница"
        n % 10 in 2..4 -> "страницы"
        else -> "страниц"
    }
    return "$n $word"
}
