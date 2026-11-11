package az.pdfstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import az.pdfstudio.ui.AppRoot
import az.pdfstudio.ui.PdfStudioTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.data?.let { vm.open(it) }   // "Birlikdə aç" → PDF Studio
        setContent { PdfStudioTheme { AppRoot(vm) } }
    }
}
