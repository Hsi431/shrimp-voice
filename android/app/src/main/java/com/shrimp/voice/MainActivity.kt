package com.shrimp.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.shrimp.voice.ui.SettingsScreen
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            grants[Manifest.permission.POST_NOTIFICATIONS] == false) {
            Timber.w("POST_NOTIFICATIONS denied - foreground notification may be hidden")
        }
        if (grants[Manifest.permission.RECORD_AUDIO] == false) {
            Timber.w("RECORD_AUDIO denied - wake word detection cannot start")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        enableEdgeToEdge()

        val permissionsToRequest = mutableListOf<String>()

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        // Required for AudioRecord PCM streaming to the voice server.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest += Manifest.permission.RECORD_AUDIO
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }

        setContent {
            SettingsScreen()
        }
    }
}
