package com.wavecourt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wavecourt.CountdownMode
import com.wavecourt.GameMode
import com.wavecourt.GameSettings
import com.wavecourt.InitialStateChoice
import com.wavecourt.RacketType
import com.wavecourt.render.RacketOverlayMode
import kotlin.math.roundToInt

/**
 * The menu is a pure function of [settings] -> every control writes back
 * through [onSettingsChange] using a `copy()`, so there is exactly one
 * source of truth (the ViewModel's GameSettings) and the menu never holds
 * its own duplicate state.
 *
 * The whole column is wrapped in `verticalScroll` so every control plus
 * the Start Game button stays reachable even when content height exceeds
 * the viewport -- e.g. landscape on phones, where vertical space is tight.
 */
@Composable
fun StartMenuScreen(
    settings: GameSettings,
    onSettingsChange: ((GameSettings) -> GameSettings) -> Unit,
    onStartGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Wave Court", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        SectionLabel("Game Mode")
        TwoWayToggle(
            leftLabel = "vs AI",
            rightLabel = "No AI",
            isLeftSelected = settings.gameMode == GameMode.VS_AI,
            onLeftClick = { onSettingsChange { it.copy(gameMode = GameMode.VS_AI) } },
            onRightClick = { onSettingsChange { it.copy(gameMode = GameMode.NO_AI) } }
        )

        SectionLabel("Initial State")
        TwoWayToggle(
            leftLabel = "Quantum Vortex",
            rightLabel = "Squeezed State",
            isLeftSelected = settings.initialState == InitialStateChoice.QUANTUM_VORTEX,
            onLeftClick = { onSettingsChange { it.copy(initialState = InitialStateChoice.QUANTUM_VORTEX) } },
            onRightClick = { onSettingsChange { it.copy(initialState = InitialStateChoice.SQUEEZED_STATE) } }
        )

        SectionLabel("Countdown")
        TwoWayToggle(
            leftLabel = "Random (6-12s)",
            rightLabel = "Fixed",
            isLeftSelected = settings.countdownMode == CountdownMode.RANDOM,
            onLeftClick = { onSettingsChange { it.copy(countdownMode = CountdownMode.RANDOM) } },
            onRightClick = { onSettingsChange { it.copy(countdownMode = CountdownMode.FIXED) } }
        )
        if (settings.countdownMode == CountdownMode.FIXED) {
            LabeledSlider(
                label = "Fixed countdown: ${settings.fixedCountdownSeconds.roundToInt()}s",
                value = settings.fixedCountdownSeconds,
                range = 3f..100f,
                onValueChange = { v -> onSettingsChange { it.copy(fixedCountdownSeconds = v) } }
            )
        }

        LabeledSlider(
            label = "Match target: first to ${settings.matchTargetScore}",
            value = settings.matchTargetScore.toFloat(),
            range = 1f..20f,
            steps = 18,
            onValueChange = { v -> onSettingsChange { it.copy(matchTargetScore = v.roundToInt()) } }
        )

        LabeledSlider(
            label = "Coupling g: ${"%.1f".format(settings.couplingG)}" +
                    if (settings.couplingG == 0f) " (linear)" else if (settings.couplingG < 0f) " (attractive)" else " (repulsive)",
            value = settings.couplingG,
            range = -12f..12f,
            onValueChange = { v -> onSettingsChange { it.copy(couplingG = v) } }
        )

        SectionLabel("Racket Type")
        TwoWayToggle(
            leftLabel = "Attractive",
            rightLabel = "Repulsive",
            isLeftSelected = settings.racketType == RacketType.ATTRACTIVE,
            onLeftClick = { onSettingsChange { it.copy(racketType = RacketType.ATTRACTIVE) } },
            onRightClick = { onSettingsChange { it.copy(racketType = RacketType.REPULSIVE) } }
        )

        SectionLabel("Racket Visualization")
        TwoWayToggle(
            leftLabel = "Visual",
            rightLabel = "Invisible",
            isLeftSelected = settings.racketOverlayMode == RacketOverlayMode.VISIBLE,
            onLeftClick = { onSettingsChange { it.copy(racketOverlayMode = RacketOverlayMode.VISIBLE) } },
            onRightClick = { onSettingsChange { it.copy(racketOverlayMode = RacketOverlayMode.INVISIBLE) } }
        )

        Spacer(Modifier.height(32.dp))
        Button(onClick = onStartGame, modifier = Modifier.fillMaxWidth()) {
            Text("Start Game", style = MaterialTheme.typography.titleMedium)
        }
        // Extra bottom margin so the button clears system nav bars / gesture
        // areas in landscape, where the viewport is shortest.
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TwoWayToggle(
    leftLabel: String,
    rightLabel: String,
    isLeftSelected: Boolean,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isLeftSelected) {
            Button(onClick = onLeftClick, modifier = Modifier.weight(1f)) { Text(leftLabel) }
        } else {
            OutlinedButton(onClick = onLeftClick, modifier = Modifier.weight(1f)) { Text(leftLabel) }
        }
        if (!isLeftSelected) {
            Button(onClick = onRightClick, modifier = Modifier.weight(1f)) { Text(rightLabel) }
        } else {
            OutlinedButton(onClick = onRightClick, modifier = Modifier.weight(1f)) { Text(rightLabel) }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Spacer(Modifier.height(12.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
}