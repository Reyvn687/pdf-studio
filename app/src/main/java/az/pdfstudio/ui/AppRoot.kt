package az.pdfstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.pdfstudio.EditorViewModel
import az.pdfstudio.core.DocumentFormats

@Composable
fun AppRoot(vm: EditorViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.message) { vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.message = null } }
    BackHandler(vm.work != null) { if (vm.edit != null) vm.finishEdit() else vm.closeDoc() }
    Box(Modifier.fillMaxSize()) {
        if (vm.work == null) HomeScreen(vm) else EditorScreen(vm)
        vm.busy?.let {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = false) {}, Alignment.Center) {
                Card { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(14.dp)); Text(it)
                } }
            }
        }
        vm.ocrText?.let { text ->
            AlertDialog(
                onDismissRequest = { vm.ocrText = null },
                title = { Text("OCR nəticəsi") },
                text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
                confirmButton = {
                    TextButton({
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ocr", text))
                        Toast.makeText(ctx, "Kopyalandı", Toast.LENGTH_SHORT).show()
                    }) { Text("Kopyala") }
                },
                dismissButton = { TextButton({ vm.ocrText = null }) { Text("Bağla") } }
            )
        }
    }
}

@Composable
fun HomeScreen(vm: EditorViewModel) {
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.open(u) } }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { if (it.isNotEmpty()) vm.open(*it.toTypedArray()) }
    val ocrPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::ocrImage) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.linearGradient(listOf(Indigo, Cyan)), RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .statusBarsPadding().padding(28.dp, 36.dp, 28.dp, 40.dp)
        ) {
            Column {
                Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .2f)) {
                    Icon(Icons.Filled.EditNote, null, Modifier.padding(14.dp).size(36.dp), tint = Color.White)
                }
                Spacer(Modifier.height(18.dp))
                Text("PDF Studio", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Redaktə et · OCR et · Birləşdir", color = Color.White.copy(alpha = .85f))
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ActionCard(Icons.Filled.FolderOpen, "PDF aç", "Mövcud sənədi redaktə et") { openPdf.launch(DocumentFormats.pickerMimes) }
            ActionCard(Icons.Filled.Collections, "Şəkillərdən PDF", "Bir neçə şəkli bir PDF-də topla") { images.launch("image/*") }
            ActionCard(Icons.Filled.DocumentScanner, "Şəkildən mətn (OCR)", "Şəkildəki yazını tanı və kopyala") { ocrPick.launch("image/*") }
            ActionCard(Icons.Filled.Extension, "Tezliklə", "DOCX, EPUB və digər formatlar…", enabled = false) {}
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(if (enabled) 3.dp else 0.dp)
    ) {
        Row(Modifier.padding(18.dp).alpha(if (enabled) 1f else .5f), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                Icon(icon, null, Modifier.padding(12.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)) }
        }
    }
}

private fun Modifier.alpha(a: Float) = this.then(Modifier.graphicsLayer(alpha = a))
