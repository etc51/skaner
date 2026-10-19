package ru.skaner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.skaner.data.Doc
import ru.skaner.data.Docs
import ru.skaner.scan.Scanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(actions: Actions, onOpen: (String) -> Unit) {
    val docs = rememberDocs<List<Doc>?>(null) { it.list() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            docs == null -> Unit
            docs.isEmpty() -> EmptyState(Modifier.align(Alignment.Center))
            else -> DocumentGrid(docs, actions, onOpen)
        }
        ScanBar(
            onScan = { actions.scan(null) },
            onPick = { actions.pick(null) },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
        )
    }
}

@Composable
private fun DocumentGrid(docs: List<Doc>, actions: Actions, onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(152.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 128.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.statusBarsPadding().padding(top = 28.dp, bottom = 4.dp)) {
                Text("Документы", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${docs.size} · без рекламы и водяных знаков",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(docs, key = { it.id }) { doc -> DocumentCard(doc, actions, onOpen) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(doc: Doc, actions: Actions, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { Docs.get(context) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }

    Column(Modifier.combinedClickable(onClick = { onOpen(doc.id) }, onLongClick = { menu = true })) {
        Box {
            FileImage(
                store.cover(doc),
                maxSide = 600,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.76f)
                    .shadow(6.dp, MaterialTheme.shapes.medium, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
            )
            if (doc.pages.size > 1) {
                Text(
                    "${doc.pages.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Поделиться PDF") },
                    leadingIcon = { Icon(Icons.Outlined.Share, null) },
                    onClick = {
                        menu = false
                        actions.work("Собираю PDF…") {
                            val file = withContext(Dispatchers.IO) { Scanner.exportPdf(context, doc) }
                            shareFiles(context, listOf(file), "application/pdf", doc.name)
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Переименовать") },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                    onClick = { menu = false; rename = true },
                )
                DropdownMenuItem(
                    text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; delete = true },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${pagesLabel(doc.pages.size)} · ${shortDate(doc.updated)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }

    if (rename) {
        RenameDialog(doc.name, onDismiss = { rename = false }) { name ->
            rename = false
            actions.work("Сохраняю…") { withContext(Dispatchers.IO) { store.save(doc.copy(name = name)) } }
        }
    }
    if (delete) {
        ConfirmDialog("Удалить документ?", "«${doc.name}» удалится с телефона без возможности восстановить.", "Удалить", { delete = false }) {
            delete = false
            actions.work("Удаляю…") { withContext(Dispatchers.IO) { store.delete(doc.id) } }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp).padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .shadow(24.dp, RoundedCornerShape(32.dp), spotColor = Indigo)
                .background(BrandGradient, RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.DocumentScanner, null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("Сканируйте документы", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Наведите камеру на лист: края найдутся сами, тени уберутся, получится аккуратный PDF.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Feature(Icons.Outlined.VerifiedUser, "Без рекламы и водяных знаков")
            Feature(Icons.Outlined.CloudOff, "Работает без интернета")
            Feature(Icons.Outlined.PictureAsPdf, "PDF и JPG в один тап")
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Плавающая панель: большая кнопка камеры и круглая кнопка галереи. */
@Composable
fun ScanBar(onScan: () -> Unit, onPick: () -> Unit, modifier: Modifier = Modifier, scanText: String = "Сканировать") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            onClick = onScan,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.shadow(16.dp, CircleShape, spotColor = Indigo, ambientColor = Indigo),
        ) {
            Row(
                Modifier.background(BrandGradient).padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PhotoCamera, null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(scanText, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        Surface(
            onClick = onPick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 10.dp,
            modifier = Modifier.size(60.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Из галереи", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun shortDate(time: Long): String =
    SimpleDateFormat("d MMM", Locale.forLanguageTag("ru")).format(Date(time)).trimEnd('.')
