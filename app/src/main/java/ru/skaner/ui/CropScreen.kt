package ru.skaner.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.skaner.core.Point
import ru.skaner.core.Quad
import ru.skaner.data.Doc
import ru.skaner.data.Docs
import ru.skaner.data.Page
import ru.skaner.scan.Scanner
import kotlin.math.min
import kotlin.math.roundToInt

private class CropData(val doc: Doc, val page: Page, val image: ImageBitmap, val sourceWidth: Int, val sourceHeight: Int)

/** Экран углов: снимок во весь экран, четыре уголка листа можно перетащить. */
@Composable
fun CropScreen(docId: String, pageId: String, isNew: Boolean, actions: Actions, onDone: () -> Unit, onCancel: (docLeft: Boolean) -> Unit) {
    val context = LocalContext.current
    val docs = remember { Docs.get(context) }
    val data by produceState<CropData?>(null, docId, pageId) {
        value = withContext(Dispatchers.IO) {
            val doc = docs.get(docId) ?: return@withContext null
            val page = doc.pages.firstOrNull { it.id == pageId } ?: return@withContext null
            val (w, h) = Scanner.sourceSize(context, docId, page)
            val bitmap: Bitmap = Scanner.decodeFile(docs.sourceFile(docId, pageId), 2000) ?: return@withContext null
            CropData(doc, page, bitmap.asImageBitmap(), w, h)
        }
    }
    // Углы в координатах исходного снимка.
    var quad by remember { mutableStateOf<Quad?>(null) }
    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        quad = d.page.quad ?: Quad.full(d.sourceWidth.toFloat(), d.sourceHeight.toFloat())
    }

    fun cancel() {
        val d = data
        if (!isNew || d == null) {
            onCancel(true)
            return
        }
        actions.work("Отменяю…") {
            val left = withContext(Dispatchers.IO) {
                val rest = d.doc.pages.filter { it.id != pageId }
                if (rest.isEmpty()) docs.delete(docId) else docs.save(d.doc.copy(pages = rest))
                rest.isNotEmpty()
            }
            onCancel(left)
        }
    }
    BackHandler { cancel() }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0C10))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { cancel() }) { Icon(Icons.Outlined.Close, "Отмена", tint = Color.White) }
                Text("Края листа", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Text(
                "Перетащите уголки к краям листа",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                val d = data
                val q = quad
                if (d == null || q == null) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    CornerEditor(d, q) { quad = it }
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DarkAction(Icons.Outlined.CropFree, "Весь кадр") {
                    data?.let { quad = Quad.full(it.sourceWidth.toFloat(), it.sourceHeight.toFloat()) }
                }
                DarkAction(Icons.Outlined.AutoFixHigh, "Авто") {
                    val d = data ?: return@DarkAction
                    actions.work("Ищу края листа…") {
                        val found = withContext(Dispatchers.IO) { Scanner.detect(context, docId, d.page) }
                        quad = found ?: Quad.full(d.sourceWidth.toFloat(), d.sourceHeight.toFloat())
                    }
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = {
                        val d = data ?: return@Surface
                        val q = quad ?: return@Surface
                        actions.work("Выравниваю страницу…") {
                            withContext(Dispatchers.IO) {
                                val fresh = docs.get(docId) ?: d.doc
                                val page = d.page.copy(quad = Quad.of(q.points))
                                docs.save(fresh.copy(pages = fresh.pages.map { if (it.id == pageId) page else it }, updated = System.currentTimeMillis()))
                                Scanner.render(context, docId, page)
                            }
                            onDone()
                        }
                    },
                    shape = CircleShape,
                    color = Color.Transparent,
                ) {
                    Row(
                        Modifier.background(BrandGradient).padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Check, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Готово", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun DarkAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.White.copy(alpha = 0.1f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CornerEditor(data: CropData, quad: Quad, onChange: (Quad) -> Unit) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val currentQuad by androidx.compose.runtime.rememberUpdatedState(quad)
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(data) {
                var fit = Rect.Zero
                detectDragGestures(
                    onDragStart = { start ->
                        fit = fitRect(data, Size(size.width.toFloat(), size.height.toFloat()))
                        val corners = currentQuad.points.map { toScreen(it, data, fit) }
                        val nearest = corners.indices.minBy { (corners[it] - start).getDistance() }
                        dragging = if ((corners[nearest] - start).getDistance() < 56.dp.toPx()) nearest else null
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onDrag = { change, amount ->
                        val i = dragging ?: return@detectDragGestures
                        change.consume()
                        val pts = currentQuad.points.toMutableList()
                        val k = data.sourceWidth / fit.width
                        val p = pts[i]
                        pts[i] = Point(
                            (p.x + amount.x * k).coerceIn(0f, data.sourceWidth.toFloat()),
                            (p.y + amount.y * k).coerceIn(0f, data.sourceHeight.toFloat()),
                        )
                        onChange(Quad(pts[0], pts[1], pts[2], pts[3]))
                    },
                )
            },
    ) {
        val fit = fitRect(data, size)
        drawImage(
            data.image,
            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
        )
        val corners = quad.points.map { toScreen(it, data, fit) }
        val outline = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        // Всё, что вне листа, затемняем.
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(fit)
            addPath(outline)
        }
        drawPath(dim, Color.Black.copy(alpha = 0.55f))
        drawPath(outline, accent, style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round))
        corners.forEachIndexed { i, c ->
            val r = if (dragging == i) 15.dp.toPx() else 11.dp.toPx()
            drawCircle(Color.White, r, c)
            drawCircle(accent, r, c, style = Stroke(3.dp.toPx()))
        }
        dragging?.let { drawLoupe(data, fit, corners[it], accent) }
    }
}

/** Лупа над пальцем: увеличенный участок вокруг уголка, чтобы попасть точно в край. */
private fun DrawScope.drawLoupe(data: CropData, fit: Rect, corner: Offset, accent: Color) {
    val radius = 56.dp.toPx()
    val zoom = 2.5f
    val margin = 16.dp.toPx()
    // Лупа в верхнем углу на стороне, противоположной пальцу.
    val center = Offset(if (corner.x < size.width / 2) size.width - radius - margin else radius + margin, radius + margin)
    val circle = Path().apply { addOval(Rect(center, radius)) }
    clipPath(circle) {
        drawRect(Color.Black, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
        val w = fit.width * zoom
        val h = fit.height * zoom
        val left = center.x - (corner.x - fit.left) * zoom
        val top = center.y - (corner.y - fit.top) * zoom
        drawImage(data.image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
    }
    drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
    val c = 8.dp.toPx()
    drawLine(accent, Offset(center.x - c, center.y), Offset(center.x + c, center.y), 2.dp.toPx())
    drawLine(accent, Offset(center.x, center.y - c), Offset(center.x, center.y + c), 2.dp.toPx())
}

private fun fitRect(data: CropData, area: Size): Rect {
    val k = min(area.width / data.sourceWidth, area.height / data.sourceHeight)
    val w = data.sourceWidth * k
    val h = data.sourceHeight * k
    return Rect(Offset((area.width - w) / 2, (area.height - h) / 2), Size(w, h))
}

private fun toScreen(p: Point, data: CropData, fit: Rect): Offset =
    Offset(fit.left + p.x / data.sourceWidth * fit.width, fit.top + p.y / data.sourceHeight * fit.height)
