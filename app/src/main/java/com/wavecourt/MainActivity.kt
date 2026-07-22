package com.wavecourt

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.wavecourt.ui.GameScreen
import com.wavecourt.ui.StartMenuScreen

/**
 * Single-Activity Compose host. Wave Court's renderer is built on AGSL
 * (RuntimeShader), which is a hard floor of API 33 (Android 13) -- see the
 * @RequiresApi note on WaveCourtRenderer in Phase 2. Rather than silently
 * crash on older devices, this Activity checks the floor itself and shows
 * a plain-text fallback instead of ever reaching the shader code path.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WaveCourtViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        WaveCourtApp(viewModel)
                    } else {
                        UnsupportedDeviceMessage()
                    }
                }
            }
        }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun WaveCourtApp(viewModel: WaveCourtViewModel) {
    val gameState by viewModel.gameState.collectAsStateWithLifecycle()

    when (gameState) {
        GameState.MENU -> {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            StartMenuScreen(
                settings = settings,
                onSettingsChange = viewModel::updateSettings,
                onStartGame = viewModel::onStartGame,
                modifier = Modifier.fillMaxSize()
            )
        }
        // PLAYING / PAUSED / COLLAPSED / MATCH_OVER are all rendered by
        // GameScreen itself, which switches its own overlays on gameState --
        // that keeps the "is the physics surface still on screen" question
        // (yes, for all four of these) separate from "which overlay is on
        // top of it" (GameScreen's own concern).
        GameState.PLAYING, GameState.PAUSED, GameState.COLLAPSED, GameState.MATCH_OVER -> {
            GameScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun UnsupportedDeviceMessage() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Wave Court requires Android 13 (API 33) or newer for GPU shader rendering.")
    }
}