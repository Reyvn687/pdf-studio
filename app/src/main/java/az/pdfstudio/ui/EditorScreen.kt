package az.pdfstudio.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.pdfstudio.EditorViewModel
import az.pdfstudio.model.Annot
import az.pdfstudio.model.Pt
import az.pdfstudio.model.Tool
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    val current = listState.firstVisibleItemIndex
    val saveL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let(vm::save) }
    val mergeL = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.merge(it) }

    // alət dəyişəndə redaktə sessiyasını bitir
    LaunchedEffect(vm.tool) { if (vm.tool != Tool.EDIT && vm.edit != null) vm.finishEdit() }

    // sessiya başlayanda abzası ekranın yuxarı-orta hissəsinə gətir
    val sess = vm.edit
    val cfg = LocalConfiguration.current
    val dens = LocalDensity.current
    LaunchedEffect(sess) {
        sess ?: return@LaunchedEffect
        val wPx = cfg.screenWidthDp * dens.density - 24 * dens.density
        val pagePx = wPx * sess.model.pageH / sess.model.pageW
        val y = sess.model.caret(vm.selB)[1] / sess.model.pageH * pagePx
        val vh = listState.layoutInfo.viewportSize.height
        listState.scrollToItem(sess.page, max(0f, y - vh * 0.30f).toInt())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.fileName, maxLines = 1) },
                navigationIcon = { IconButton({ vm.closeDoc() }) { Icon(Icons.Filled.Close, "Bağla") } },
                actions = {
                    IconButton({ vm.undo() }, enabled = vm.annots.isNotEmpty() || vm.historySize > 0) { Icon(Icons.AutoMirrored.Filled.Undo, "Geri al") }
                    IconButton({ saveL.launch(vm.fileName) }) { Icon(Icons.Filled.Save, "Saxla") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Menyu") }
                        DropdownMenu(menu, { menu = false }) {
                            MenuItem(Icons.Filled.RotateRight, "Səhifəni fırlat") { menu = false; vm.rotate(current) }
                            MenuItem(Icons.Filled.Delete, "Səhifəni sil") { menu = false; vm.delete(current) }
                            MenuItem(Icons.Filled.CallMerge, "Başqa PDF/şəkil birləşdir") { menu = false; mergeL.launch(az.pdfstudio.core.DocumentFormats.pickerMimes) }
                            HorizontalDivider()
                            MenuItem(Icons.Filled.DocumentScanner, "Bu səhifədən mətn çıxar (OCR)") { menu = false; vm.ocrPage(current) }
                            MenuItem(Icons.Filled.ManageSearch, "Axtarıla bilən PDF et (OCR)") { menu = false; vm.makeSearchable() }
                        }
                    }
                }
            )
        },
        bottomBar = { if (vm.edit != null) EditBar(vm) else ToolBar(vm) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(vm.pageCount, key = { it }) { PageView(it, vm) }
            }
            Surface(
                Modifier.align(Alignment.TopCenter).padding(8.dp), shape = RoundedCornerShape(50),
                color = Color(0xAA000000)
            ) { Text("${current + 1} / ${vm.pageCount}", Modifier.padding(12.dp, 4.dp), color = Color.White, fontSize = 12.sp) }
            HiddenInput(vm)
        }
    }

    vm.pendingText?.let { (p, pos) ->
        var t by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.pendingText = null },
            title = { Text("Mətn əlavə et") },
            text = { OutlinedTextField(t, { t = it }, minLines = 2) },
            confirmButton = {
                TextButton({
                    if (t.isNotBlank()) vm.add(Annot.Text(p, pos.x, pos.y, t, 0.028f, vm.color))
                    vm.pendingText = null
                }) { Text("Əlavə et") }
            },
            dismissButton = { TextButton({ vm.pendingText = null }) { Text("Ləğv et") } }
        )
    }
}

/** Görünməyən giriş sahəsi: klaviatura/IME-dən mətn qəbul edir, kursor və seçimi modellə sinxronlaşdırır. */
@Composable
private fun HiddenInput(vm: EditorViewModel) {
    val s = vm.edit ?: return
    val fr = remember(s) { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    var tfv by remember(s) { mutableStateOf(TextFieldValue(s.model.flat(), TextRange(vm.selA, vm.selB))) }

    LaunchedEffect(vm.selA, vm.selB) {
        val want = TextRange(vm.selA, vm.selB)
        if (tfv.selection != want && max(want.start, want.end) <= tfv.text.length) tfv = tfv.copy(selection = want)
    }
    LaunchedEffect(s) { delay(200); fr.requestFocus(); kb?.show() }

    BasicTextField(
        value = tfv,
        onValueChange = { nv ->
            tfv = nv
            vm.onInput(nv.text, nv.selection.start, nv.selection.end)
            val flat = s.model.flat()
            if (flat != nv.text) tfv = TextFieldValue(flat, TextRange(min(vm.selA, flat.length), min(vm.selB, flat.length)))
        },
        modifier = Modifier.size(1.dp).focusRequester(fr),
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent)
    )
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)

private val palette = listOf(0xFF111827, 0xFFDC2626, 0xFF1E40AF, 0xFF16A34A, 0xFFFACC15).map { it.toInt() }

@Composable
private fun ToolBar(vm: EditorViewModel) {
    Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                palette.forEach { c -> ColorDot(c, vm.color == c) { vm.color = c } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(Icons.Filled.PanTool, Tool.VIEW, vm)
                ToolButton(Icons.Filled.EditNote, Tool.EDIT, vm)
                ToolButton(Icons.Filled.TextFields, Tool.TEXT, vm)
                ToolButton(Icons.Filled.Highlight, Tool.HIGHLIGHT, vm)
                ToolButton(Icons.Filled.Brush, Tool.DRAW, vm)
                ToolButton(Icons.Filled.CropSquare, Tool.COVER, vm)
            }
        }
    }
}

@Composable
private fun ColorDot(c: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(6.dp).size(26.dp).background(Color(c), CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.LightGray, CircleShape)
            .clickable(onClick = onClick)
    )
}

/** Redaktə zamanı klaviaturanın üstündəki alət çubuğu (B / I / ölçü / rəng / bitir) */
@Composable
private fun EditBar(vm: EditorViewModel) {
    val m = vm.edit!!.model
    vm.editRev // dəyişiklikdə yenilə
    val cur = m.chars.getOrNull(min(max(vm.selB, 0), m.chars.size - 1))
    Surface(shadowElevation = 12.dp, color = Color(0xFF111827)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                palette.forEach { c -> ColorDot(c, cur?.color == c) { vm.setTextColor(c) } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ vm.cancelEdit() }) { Icon(Icons.Filled.Close, "Ləğv et", tint = Color.White) }
                FilterChip(cur?.bold == true, { vm.toggleBold() }, { Text("B", fontWeight = FontWeight.Bold) })
                FilterChip(cur?.italic == true, { vm.toggleItalic() }, { Text("I", fontStyle = FontStyle.Italic) })
                IconButton({ vm.sizeStep(-0.5f) }) { Icon(Icons.Filled.Remove, "Kiçilt", tint = Color.White) }
                Text("%.1f".format(cur?.size ?: 0f), color = Color.White)
                IconButton({ vm.sizeStep(0.5f) }) { Icon(Icons.Filled.Add, "Böyüt", tint = Color.White) }
                FilledIconButton({ vm.finishEdit() }) { Icon(Icons.Filled.Check, "Hazırdır") }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, tool: Tool, vm: EditorViewModel) {
    val sel = vm.tool == tool
    Column(Modifier.clickable { vm.tool = tool }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(14.dp), color = if (sel) MaterialTheme.colorScheme.primary else Color.Transparent) {
            Icon(icon, tool.label, Modifier.padding(10.dp), tint = if (sel) Color.White else MaterialTheme.colorScheme.onSurface)
        }
        Text(tool.label, fontSize = 11.sp)
    }
}

@Composable
private fun PageView(index: Int, vm: EditorViewModel) {
    val bmp by produceState<Bitmap?>(null, index, vm.revision) { value = vm.render(index, 1400) }
    LaunchedEffect(index, vm.revision, vm.tool) { if (vm.tool == Tool.EDIT) vm.ensureBlocks(index) }
    val shown = if (vm.edit?.page == index) (vm.preview ?: bmp) else bmp
    Card(Modifier.fillMaxWidth().padding(12.dp, 6.dp), shape = RoundedCornerShape(6.dp), elevation = CardDefaults.cardElevation(4.dp)) {
        if (shown == null) Box(Modifier.fillMaxWidth().aspectRatio(0.7f), Alignment.Center) { CircularProgressIndicator() }
        else Box(Modifier.fillMaxWidth().aspectRatio(shown.width.toFloat() / shown.height)) {
            Image(shown.asImageBitmap(), null, Modifier.matchParentSize())
            Overlay(index, vm)
        }
    }
}

@Composable
private fun Overlay(page: Int, vm: EditorViewModel) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var cur by remember { mutableStateOf<Offset?>(null) }
    val ink = remember { mutableStateListOf<Offset>() }
    val measurer = rememberTextMeasurer()
    val sess = vm.edit

    Canvas(
        Modifier.matchParentSize().onSizeChanged { size = it }
            // ---- toxunuşlar ----
            .pointerInput(vm.tool, size, sess) {
                val w = size.width.toFloat(); val h = size.height.toFloat()
                if (w == 0f) return@pointerInput
                when (vm.tool) {
                    Tool.TEXT -> detectTapGestures { vm.pendingText = page to Pt(it.x / w, it.y / h) }
                    Tool.EDIT -> {
                        if (sess != null && sess.page == page) {
                            val m = sess.model; val sx = m.pageW / w; val sy = m.pageH / h
                            detectTapGestures(
                                onLongPress = { o -> vm.selectWord(m.hit(o.x * sx, o.y * sy)) },
                                onTap = { o ->
                                    if (m.inside(o.x * sx, o.y * sy)) vm.setCaret(m.hit(o.x * sx, o.y * sy)) else vm.finishEdit()
                                })
                        } else if (sess != null) {
                            detectTapGestures { vm.finishEdit() }
                        } else {
                            detectTapGestures { o ->
                                vm.blocks[page]?.firstOrNull { b ->
                                    val px = o.x / w * b.pageW; val py = o.y / h * b.pageH
                                    px in (b.l - 4f)..(b.r + 4f) && py in b.t..b.b
                                }?.let { b -> vm.startEdit(b, o.x / w * b.pageW, o.y / h * b.pageH) }
                            }
                        }
                    }
                    else -> {}
                }
            }
            // ---- sürüşdürmə ----
            .pointerInput(vm.tool, size, vm.color, sess) {
                val w = size.width.toFloat(); val h = size.height.toFloat()
                if (w == 0f) return@pointerInput
                when (vm.tool) {
                    Tool.DRAW -> detectDragGestures(
                        onDragStart = { ink.clear(); ink.add(it) },
                        onDrag = { c, _ -> ink.add(c.position) },
                        onDragEnd = {
                            if (ink.size > 1) vm.add(Annot.Ink(page, ink.map { Pt(it.x / w, it.y / h) }, vm.color))
                            ink.clear()
                        })
                    Tool.HIGHLIGHT, Tool.COVER -> detectDragGestures(
                        onDragStart = { start = it; cur = it },
                        onDrag = { c, _ -> cur = c.position },
                        onDragEnd = {
                            val s = start; val e = cur
                            if (s != null && e != null) {
                                val l = minOf(s.x, e.x) / w; val r = maxOf(s.x, e.x) / w
                                val t = minOf(s.y, e.y) / h; val bt = maxOf(s.y, e.y) / h
                                if (r - l > .005f && bt - t > .003f)
                                    vm.add(if (vm.tool == Tool.HIGHLIGHT) Annot.Highlight(page, l, t, r, bt, vm.color) else Annot.Cover(page, l, t, r, bt))
                            }
                            start = null; cur = null
                        })
                    Tool.EDIT -> {
                        if (sess != null && sess.page == page) {
                            val m = sess.model; val sx = m.pageW / w; val sy = m.pageH / h
                            var anchor = 0
                            detectDragGestures(
                                onDragStart = { o -> anchor = m.hit(o.x * sx, o.y * sy); vm.setSelection(anchor, anchor) },
                                onDrag = { c, _ -> vm.setSelection(anchor, m.hit(c.position.x * sx, c.position.y * sy)) })
                        } else Unit
                    }
                    else -> {}
                }
            }
    ) {
        val w = this.size.width; val h = this.size.height

        // redaktə rejimi: abzas çərçivələri
        if (vm.tool == Tool.EDIT && sess == null) vm.blocks[page]?.forEach { b ->
            val tl = Offset(b.l / b.pageW * w, b.t / b.pageH * h)
            val sz = Size((b.r - b.l) / b.pageW * w, (b.b - b.t) / b.pageH * h)
            drawRect(Color(0x1A4F46E5), tl, sz)
            drawRect(Color(0xFF4F46E5), tl, sz, style = Stroke(width = 2f))
        }

        // aktiv sessiya: seçim + kursor
        if (sess != null && sess.page == page) {
            vm.editRev
            val m = sess.model; val sx = w / m.pageW; val sy = h / m.pageH
            val a = min(vm.selA, vm.selB); val b = max(vm.selA, vm.selB)
            if (b > a) m.selRects(a, b).forEach { r ->
                drawRect(Color(0x554F46E5), Offset(r[0] * sx, r[1] * sy), Size((r[2] - r[0]) * sx, (r[3] - r[1]) * sy))
            }
            val c = m.caret(vm.selB)
            drawLine(Color(0xFF4F46E5), Offset(c[0] * sx, c[1] * sy), Offset(c[0] * sx, c[2] * sy), strokeWidth = 3f)
        }

        vm.annots.filter { it.page == page }.forEach { a ->
            when (a) {
                is Annot.Highlight -> drawRect(Color(a.color).copy(alpha = .35f), Offset(a.l * w, a.t * h), Size((a.r - a.l) * w, (a.b - a.t) * h))
                is Annot.Cover -> drawRect(Color.White, Offset(a.l * w, a.t * h), Size((a.r - a.l) * w, (a.b - a.t) * h))
                is Annot.Ink -> drawPath(Path().apply {
                    a.pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * w, p.y * h) else lineTo(p.x * w, p.y * h) }
                }, Color(a.color), style = Stroke(width = w * 0.003f))
                is Annot.Text -> drawText(measurer, a.text, Offset(a.x * w, a.y * h), TextStyle(color = Color(a.color), fontSize = (a.size * w).toSp()))
            }
        }
        if (ink.size > 1) drawPath(Path().apply { ink.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } },
            Color(vm.color), style = Stroke(width = w * 0.003f))
        val s = start; val e = cur
        if (s != null && e != null) drawRect(
            if (vm.tool == Tool.COVER) Color.Gray.copy(alpha = .5f) else Color(vm.color).copy(alpha = .35f),
            Offset(minOf(s.x, e.x), minOf(s.y, e.y)), Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y))
        )
    }
}
