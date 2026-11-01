package app.glyph

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    private val vm: Vm by viewModels()
    private var askedAudio = false

    private val askAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkPermissions()
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.setFolder(uri)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        vm.onPickFolder = { pickFolder.launch(null) }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            if (!askedAudio) {
                askedAudio = true
                askAudio.launch(p)
            }
            return
        }
        if (vm.folderUri.value == null) {
            pickFolder.launch(null)
        } else if (vm.tracks.value.isEmpty()) {
            vm.load()
        }
    }
}
