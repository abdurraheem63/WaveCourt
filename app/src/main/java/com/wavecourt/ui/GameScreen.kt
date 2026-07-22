package com.wavecourt.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.wavecourt.CollapseResult
import com.wavecourt.GameMode
import com.wavecourt.GameSettings
import com.wavecourt.GameState
import com.wavecourt.RacketStatePublic
import com.wavecourt.WaveCourtViewModel
import com.wavecourt.Winner
import com.wavecourt.render.RacketOverlayMode
import com.wavecourt.render.WaveCourtRenderer

/**
 * Renders the live match plus every state-dependent overlay. WaveCourtRenderer
 * itself only cares about PLAYING vs "not playing" indirectly (it just draws
 * whatever the density extractor last published); the overlays here are what
 * actually communicate PAUSED / COLLAPSED / MATCH_OVER to the player.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun GameScreen(viewModel: WaveCourtViewModel, modifier: Modifier = Modifier) {
    val gameState by viewModel.gameState.collectAsStateWithLifecycle()
    val playerScore by viewModel.playerScore.collectAsStateWithLifecycle()
    val aiScore by viewModel.aiScore.collectAsStateWithLifecycle()
    val countdownSeconds by viewModel.countdownSeconds.collectAsStateWithLifecycle()
    val collapseResult by viewModel.collapseResult.collectAsStateWithLifecycle()
    val playerProbability by viewModel.playerProbability.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playerRacket by viewModel.playerRacketPosition.collectAsStateWithLifecycle()
    val aiRacket by viewModel.aiRacketPosition.collectAsStateWithLifecycle()

    // GameScreen is only ever composed for PLAYING / PAUSED / COLLAPSED /
    // MATCH_OVER (MainActivity routes MENU to StartMenuScreen instead), so
    // an unconditional BackHandler here already covers "all active game
    // states" -- no need to gate on gameState. Quitting via system back
    // should behave exactly like the in-UI Quit button: full score reset.
    BackHandler(enabled = true) {
        viewModel.onQuitToMenu()
    }

    val racketUvPositions = buildList {
        if (settings.racketOverlayMode == RacketOverlayMode.VISIBLE) {
            playerRacket?.let { add(it.toUv(viewModel.gridWidth, viewModel.gridHeight)) }
            if (settings.gameMode == GameMode.VS_AI) {
                aiRacket?.let { add(it.toUv(viewModel.gridWidth, viewModel.gridHeight)) }
            }
        }
    }

    // netX is still the live logical divider (scoring, probability split,
    // AI territory -- see WaveCourtViewModel) even though it no longer
    // pushes back on the wave physically; this just converts it to a
    // normalized x for drawing the divider line at the right place
    // regardless of screen size.
    val netUvX = viewModel.densityExtractor.netX.toFloat() / viewModel.gridWidth

    // Shared text style for all HUD text: white, bold, with a soft black
    // drop shadow so it stays legible against the shader's brightest
    // (white) density regions as well as its darkest (black) ones.
    val hudShadow = Shadow(
        color = Color.Black.copy(alpha = 0.85f),
        offset = Offset(1.5f, 1.5f),
        blurRadius = 6f
    )

    Box(modifier = modifier.fillMaxSize()) {
        WaveCourtRenderer(
            densityExtractor = viewModel.densityExtractor,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(gameState) {
                    if (gameState != GameState.PLAYING) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            viewModel.onPlayerRacketMove(
                                offset.x / size.width * viewModel.gridWidth,
                                offset.y / size.height * viewModel.gridHeight
                            )
                        },
                        onDrag = { change, _ ->
                            viewModel.onPlayerRacketMove(
                                change.position.x / size.width * viewModel.gridWidth,
                                change.position.y / size.height * viewModel.gridHeight
                            )
                        },
                        onDragEnd = { viewModel.onPlayerRacketRelease() },
                        onDragCancel = { viewModel.onPlayerRacketRelease() }
                    )
                },
            racketPositions = racketUvPositions,
            racketOverlayMode = settings.racketOverlayMode
        )

        // Purely decorative now that the net has no physics potential --
        // this is the ONLY thing that still shows the player where the
        // court is split, so it needs to read clearly over both the
        // shader's brightest and darkest regions.
        NetDivider(netUvX = netUvX, modifier = Modifier.fillMaxSize())

        if (gameState == GameState.COLLAPSED) {
            collapseResult?.let { CollapseFlash(it, modifier = Modifier.fillMaxSize()) }
        }

        // HUD: score + probability bar (top), countdown (center-top), pause (top-left).
        // A downward-fading black scrim sits behind the whole HUD so text/icons
        // stay readable even when the wavefunction's bright core is directly
        // behind them, independent of the per-element shadow below.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                    )
                )
                .padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Always rendered: GameScreen itself is never composed for
                // MENU, so this box is present in PLAYING, PAUSED,
                // COLLAPSED, and MATCH_OVER alike. onPause()'s own guard
                // (PLAYING -> PAUSED only) means tapping it outside PLAYING
                // is an inert no-op, same behavior as before -- this just
                // makes the affordance visible everywhere it's clickable-or-not.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = { viewModel.onPause() }) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = "Pause",
                            tint = Color.White
                        )
                    }
                }
                Text(
                    "$playerScore  -  $aiScore",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        shadow = hudShadow
                    )
                )
                Box(modifier = Modifier.size(48.dp))
            }

            LinearProgressIndicator(
                progress = { playerProbability.coerceIn(0f, 1f) },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(6.dp)
            )

            if (gameState == GameState.PLAYING) {
                Text(
                    text = "%.1f".format(countdownSeconds),
                    style = MaterialTheme.typography.displaySmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        shadow = hudShadow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        if (gameState == GameState.PAUSED) {
            PausedOverlay(
                onResume = { viewModel.onResume() },
                onResetWave = { viewModel.onResetWave() },
                onQuit = { viewModel.onQuitToMenu() },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (gameState == GameState.COLLAPSED) {
            CollapsedOverlay(
                result = collapseResult,
                playerScore = playerScore,
                aiScore = aiScore,
                onNextRound = { viewModel.onNextRound() },
                onResetWave = { viewModel.onResetWave() },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (gameState == GameState.MATCH_OVER) {
            MatchOverOverlay(
                winner = if (playerScore > aiScore) Winner.PLAYER else Winner.AI,
                onPlayAgain = { viewModel.onPlayAgain() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun RacketStatePublic.toUv(gridWidth: Int, gridHeight: Int): Offset =
    Offset((x + 0.5f) / gridWidth, (y + 0.5f) / gridHeight)

/**
 * Vertical dashed line marking the court split at [netUvX] (normalized,
 * 0..1). Drawn as two passes -- a soft dark backing stroke first, then a
 * bright dashed line on top -- the same "shadow behind bright content"
 * trick the HUD text uses via `hudShadow`, so it stays legible whether
 * it's crossing the shader's near-black background or its near-white
 * core.
 */
@Composable
private fun NetDivider(netUvX: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val x = netUvX * size.width
        drawLine(
            color = Color.Black.copy(alpha = 0.45f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 7f
        )
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f), 0f)
        )
    }
}

/** Brief ring flash at the collapse coordinate -- purely decorative, drawn once per COLLAPSED entry. */
@Composable
private fun CollapseFlash(result: CollapseResult, modifier: Modifier = Modifier) {
    val uvX = (result.gridX + 0.5f) / result.gridWidth
    val uvY = (result.gridY + 0.5f) / result.gridHeight
    val flashColor = if (result.winner == Winner.PLAYER) Color(0xFF66FFCC) else Color(0xFFFF5555)
    Canvas(modifier = modifier) {
        val center = Offset(uvX * size.width, uvY * size.height)
        drawCircle(color = flashColor, radius = 28f, center = center, style = Stroke(width = 6f))
        drawCircle(color = flashColor.copy(alpha = 0.35f), radius = 48f, center = center, style = Stroke(width = 3f))
    }
}

@Composable
private fun PausedOverlay(
    onResume: () -> Unit,
    onResetWave: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.padding(24.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Paused", style = MaterialTheme.typography.headlineMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                // Lets the player clear built-up interference and respawn
                // the chosen initial state without losing score or leaving
                // the round -- see onResetWave() in the ViewModel.
                Button(onClick = onResetWave, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text("Reset Wave")
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                Button(onClick = onQuit, modifier = Modifier.fillMaxWidth()) { Text("Quit to Menu") }
            }
        }
    }
}

/**
 * Compact "who scored" badge shown while gameState == COLLAPSED. Deliberately
 * small and corner-positioned (rather than the old full-width bottom card) so
 * it never competes with [CollapseFlash] or the wavefunction itself for the
 * player's attention -- the whole point of this overlay is to confirm what
 * just happened without stealing the view of where it happened.
 *
 * Positioned top-end, below the HUD row, since that's the one screen region
 * the HUD, net divider, and collapse flash never occupy.
 */
@Composable
private fun CollapsedOverlay(
    result: CollapseResult?,
    playerScore: Int,
    aiScore: Int,
    onNextRound: () -> Unit,
    onResetWave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badgeShadow = Shadow(
        color = Color.Black.copy(alpha = 0.85f),
        offset = Offset(1f, 1f),
        blurRadius = 4f
    )
    Box(
        modifier = modifier.padding(top = 96.dp, end = 16.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Card(
            modifier = Modifier.widthIn(max = 180.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f))
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val label = when (result?.winner) {
                    Winner.PLAYER -> "Player Scores!"
                    Winner.AI -> "AI Scores!"
                    null -> "Round Over"
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        shadow = badgeShadow
                    )
                )
                Text(
                    "$playerScore - $aiScore",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        shadow = badgeShadow
                    )
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onNextRound,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Next Round", style = MaterialTheme.typography.labelMedium)
                    }
                    // Same "clear the board" escape hatch as the pause menu, in
                    // case the player wants a fresh wave rather than continuing
                    // with whatever the next round's default initial state is.
                    // Icon-only here to keep the badge compact.
                    IconButton(onClick = onResetWave, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Reset Wave",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchOverOverlay(winner: Winner, onPlayAgain: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.padding(24.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val label = if (winner == Winner.PLAYER) "Player Wins!" else "AI Wins!"
                Text(label, style = MaterialTheme.typography.headlineMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                Button(onClick = onPlayAgain, modifier = Modifier.fillMaxWidth()) { Text("Play Again") }
            }
        }
    }
}