package com.manumusicprod.playcam

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.manumusicprod.playcam.ui.CameraScreen
import com.manumusicprod.playcam.ui.GalleryScreen
import com.manumusicprod.playcam.ui.HomeScreen
import com.manumusicprod.playcam.ui.KeyboardScreen
import com.manumusicprod.playcam.ui.ComputerScreen
import com.manumusicprod.playcam.ui.MmpTheme
import com.manumusicprod.playcam.ui.Mmp
import com.manumusicprod.playcam.ui.PlayerScreen
import com.manumusicprod.playcam.ui.SettingsScreen

enum class Screen { HOME, CAMERA, COMPUTER, KEYBOARD, SETTINGS, GALLERY, PLAYER }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation(PlayCamApp.instance.prefs.orientationMode)
        setContent { MmpTheme { AppRoot(::applyOrientation) } }
    }

    fun applyOrientation(mode: Int) {
        requestedOrientation = when (mode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        PlayCamApp.instance.camera.updateRotation()
    }
}

@Composable
fun AppRoot(setOrientation: (Int) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var back by rememberSaveable { mutableStateOf(Screen.HOME) }
    val go: (Screen) -> Unit = { s -> back = if (s == Screen.HOME) Screen.HOME else screen; screen = s }
    val recorder = PlayCamApp.instance.recorder

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.CAMERA && recorder.isBusy) return@BackHandler
        screen = when (screen) {
            Screen.PLAYER -> Screen.GALLERY
            Screen.COMPUTER, Screen.KEYBOARD, Screen.SETTINGS -> if (back == Screen.CAMERA) Screen.CAMERA else Screen.HOME
            else -> Screen.HOME
        }
    }

    Surface(Modifier.fillMaxSize(), color = Mmp.Ebony) {
        when (screen) {
            Screen.HOME -> HomeScreen(go)
            Screen.CAMERA -> CameraScreen(go, setOrientation) { id -> sessionId = id; screen = Screen.PLAYER }
            Screen.COMPUTER -> ComputerScreen { screen = if (back == Screen.CAMERA) Screen.CAMERA else Screen.HOME }
            Screen.KEYBOARD -> KeyboardScreen { screen = if (back == Screen.CAMERA) Screen.CAMERA else Screen.HOME }
            Screen.SETTINGS -> SettingsScreen(setOrientation) { screen = if (back == Screen.CAMERA) Screen.CAMERA else Screen.HOME }
            Screen.GALLERY -> GalleryScreen({ screen = Screen.HOME }) { id -> sessionId = id; screen = Screen.PLAYER }
            Screen.PLAYER -> PlayerScreen(sessionId) { screen = Screen.GALLERY }
        }
    }
}
