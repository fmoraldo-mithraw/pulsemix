package com.pulsemix.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pulsemix.app.ui.LibraryScreen
import com.pulsemix.app.ui.PlayerScreen

private val PulseColors = darkColorScheme(
    primary = Color(0xFFB497FF),
    onPrimary = Color(0xFF1B1730),
    secondary = Color(0xFF8AE0C8),
    background = Color(0xFF14111F),
    surface = Color(0xFF1D1930),
    surfaceVariant = Color(0xFF2A2442),
    onBackground = Color(0xFFEDE9F7),
    onSurface = Color(0xFFEDE9F7)
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = PulseColors) {
                val vm: PlayerViewModel = viewModel()
                var screen by remember { mutableStateOf(0) }

                val folderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        vm.onFolderPicked(uri)
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = screen == 0,
                                onClick = { screen = 0 },
                                icon = { Icon(Icons.Rounded.PlayCircle, null) },
                                label = { Text("Lecteur") }
                            )
                            NavigationBarItem(
                                selected = screen == 1,
                                onClick = { screen = 1 },
                                icon = { Icon(Icons.Rounded.LibraryMusic, null) },
                                label = { Text("Bibliothèque") }
                            )
                        }
                    }
                ) { padding ->
                    val mod = Modifier.padding(padding)
                    when (screen) {
                        0 -> PlayerScreen(vm, mod, onGoLibrary = { screen = 1 })
                        else -> LibraryScreen(vm, mod, onPickFolder = {
                            folderPicker.launch(null)
                        })
                    }
                }
            }
        }
    }
}
