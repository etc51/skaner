package ru.skaner.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.skaner.core.Filter
import ru.skaner.data.Doc
import ru.skaner.data.Docs
import ru.skaner.data.Page
import ru.skaner.scan.Scanner

/** Документ: страницы листаются, под ними фильтры и действия со страницей. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(docId: String, actions: Actions, startAtEnd: Boolean, onBack: () -> Unit, onEditCorners: (String) -> Unit) {
    val context = LocalContext.current
    val docs = remember { Docs.get(context) }
    val doc = rememberDocs<Doc?>(null, docId) { it.get(docId) } ?: return
    if (doc.pages.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // После добавления страницы открываем её, а не первую.
    val pager = rememberPagerState(initialPage = if (startAtEnd) doc.pages.lastIndex else 0) { doc.pages.size }
    val scope = rememberCoroutineScope()
    val index = pager.currentPage.coerceIn(0, doc.pages.lastIndex)
    val page = doc.pages[index]

    var menu by remember { mutableStateOf(false) }
    var shareSheet by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var deletePage by remember { mutableStateOf(false) }
    var deleteDoc by remember { mutableStateOf(false) }

    // После добавления страницы показываем последнюю.
    var knownPages by remember { mutableStateOf(doc.pages.size) }
    LaunchedEffect(doc.pages.size) {
        if (doc.pages.size > knownPages) pager.animateScrollToPage(doc.pages.lastIndex)
        knownPages = doc.pages.size
    }

    /** Меняет страницу, сохраняет и перерисовывает. */
    fun updatePage(message: String, change: (Page) -> Page) {
        actions.work(message) {
            withContext(Dispatchers.IO) {
                val fresh = docs.get(docId) ?: return@withContext
                val updated = change(fresh.pages.first { it.id == page.id })
                docs.save(fresh.copy(pages = fresh.pages.map { if (it.id == page.id) updated else it }, updated = System.currentTimeMillis()))
                Scanner.render(context, docId, updated)
            }
        }
    }

    fun movePage(delta: Int) {
        val to = index + delta
        if (to !in doc.pages.indices) return
        actions.work("Переставляю…") {
            withContext(Dispatchers.IO) {
                val pages = doc.pages.toMutableList().apply { add(to, removeAt(index)) }
                docs.save(doc.copy(pages = pages, updated = System.currentTimeMillis()))
            }
            pager.scrollToPage(to)
        }
    }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            actions.work("Сохраняю PDF…") {
                withContext(Dispatchers.IO) {
                    val file = Scanner.exportPdf(context, doc)
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                android.widget.Toast.makeText(context, "PDF сохранён", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Шапка
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
            Column(Modifier.weight(1f).clickable { rename = true }.padding(vertical = 4.dp)) {
                Text(doc.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Страница ${index + 1} из ${doc.pages.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                        onClick = { menu = false; rename = true },
                    )
                    if (doc.pages.size > 1) {
                        DropdownMenuItem(
                            text = { Text("Страницу левее") },
                            enabled = index > 0,
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                            onClick = { menu = false; movePage(-1) },
                        )
                        DropdownMenuItem(
                            text = { Text("Страницу правее") },
                            enabled = index < doc.pages.lastIndex,
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
                            onClick = { menu = false; movePage(1) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Удалить документ", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; deleteDoc = true },
                    )
                }
            }
        }

        // Страницы
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
            pageSpacing = 16.dp,
            key = { doc.pages[it].id },
        ) { i ->
            val p = doc.pages[i]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FileImage(
                    docs.pageFile(docId, p.id),
                    maxSide = 1600,
                    modifier = Modifier
                        .shadow(14.dp, RoundedCornerShape(10.dp), ambientColor = Color(0x40000000), spotColor = Color(0x40000000))
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }

        // Фильтры
        FilterRow(docId, page, onPick = { f -> if (f != page.filter) updatePage("Применяю фильтр…") { it.copy(filter = f) } })

        // Действия
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolButton(Icons.Outlined.AddAPhoto, "Добавить") { addSheet = true }
            ToolButton(Icons.Outlined.Crop, "Края") { onEditCorners(page.id) }
            ToolButton(Icons.Outlined.RotateRight, "Повернуть") { updatePage("Поворачиваю…") { it.copy(rotation = (it.rotation + 90) % 360) } }
            ToolButton(Icons.Outlined.Delete, "Удалить") { deletePage = true }
        }

        // Главная кнопка
        Surface(
            onClick = { shareSheet = true },
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)
                .shadow(12.dp, CircleShape, spotColor = Indigo, ambientColor = Indigo),
        ) {
            Row(
                Modifier.background(BrandGradient).padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Share, null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text("Поделиться", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (shareSheet) {
        ModalBottomSheet(onDismissRequest = { shareSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(doc.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pagesLabel(doc.pages.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                SheetOption(Icons.Outlined.PictureAsPdf, "Отправить PDF", "Один файл со всеми страницами") {
                    shareSheet = false
                    actions.work("Собираю PDF…") {
                        val file = withContext(Dispatchers.IO) { Scanner.exportPdf(context, doc) }
                        shareFiles(context, listOf(file), "application/pdf", doc.name)
                    }
                }
                SheetOption(Icons.Outlined.Image, "Отправить фото JPG", "Каждая страница отдельной картинкой") {
                    shareSheet = false
                    actions.work("Готовлю фото…") {
                        val files = withContext(Dispatchers.IO) { Scanner.exportJpgs(context, doc) }
                        shareFiles(context, files, "image/jpeg", doc.name)
                    }
                }
                SheetOption(Icons.Outlined.SaveAlt, "Сохранить PDF в файлы", "В «Загрузки» или любую папку") {
                    shareSheet = false
                    savePdf.launch("${Scanner.fileName(doc.name)}.pdf")
                }
            }
        }
    }

    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Добавить страницу", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                SheetOption(Icons.Outlined.AddAPhoto, "Снять камерой", "Края листа найдутся сами") {
                    addSheet = false
                    actions.scan(docId)
                }
                SheetOption(Icons.Outlined.PhotoLibrary, "Выбрать из галереи", "Можно сразу несколько фото") {
                    addSheet = false
                    actions.pick(docId)
                }
            }
        }
    }

    if (rename) {
        RenameDialog(doc.name, onDismiss = { rename = false }) { name ->
            rename = false
            actions.work("Сохраняю…") { withContext(Dispatchers.IO) { docs.save(doc.copy(name = name)) } }
        }
    }
    if (deletePage) {
        ConfirmDialog("Удалить страницу?", "Страница ${index + 1} удалится из документа.", "Удалить", { deletePage = false }) {
            deletePage = false
            actions.work("Удаляю…") {
                val left = withContext(Dispatchers.IO) {
                    val rest = doc.pages.filter { it.id != page.id }
                    if (rest.isEmpty()) docs.delete(docId) else docs.save(doc.copy(pages = rest, updated = System.currentTimeMillis()))
                    rest.size
                }
                if (left == 0) onBack() else scope.launch { pager.scrollToPage(index.coerceAtMost(left - 1)) }
            }
        }
    }
    if (deleteDoc) {
        ConfirmDialog("Удалить документ?", "«${doc.name}» удалится с телефона без возможности восстановить.", "Удалить", { deleteDoc = false }) {
            deleteDoc = false
            actions.work("Удаляю…") {
                withContext(Dispatchers.IO) { docs.delete(docId) }
                onBack()
            }
        }
    }
}

/** Ряд фильтров с живыми миниатюрами текущей страницы. */
@Composable
private fun FilterRow(docId: String, page: Page, onPick: (Filter) -> Unit) {
    val context = LocalContext.current
    val previews by produceState<Map<Filter, Bitmap>>(emptyMap(), docId, page.id, page.quad, page.rotation) {
        value = withContext(Dispatchers.IO) {
            // Миниатюру строим из снимка без фильтра: выравниваем его как оригинал и прогоняем каждый фильтр.
            val base = Scanner.preview(context, docId, page, 160) ?: return@withContext emptyMap()
            Filter.entries.associateWith { f ->
                Scanner.applyFilter(base.copy(Bitmap.Config.ARGB_8888, true), f)
            }
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        items(Filter.entries) { f ->
            val selected = f == page.filter
            val accent = MaterialTheme.colorScheme.primary
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onPick(f) }.padding(4.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 58.dp, height = 72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            if (selected) BorderStroke(2.5.dp, accent) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            RoundedCornerShape(12.dp),
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    previews[f]?.let {
                        Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (selected) 2.5.dp else 1.dp).clip(RoundedCornerShape(10.dp)))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    f.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SheetOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
