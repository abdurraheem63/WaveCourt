package com.wavecourt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavecourt.physics.ADICrankNicolsonSolver
import com.wavecourt.physics.Grid
import com.wavecourt.render.DensityExtractor
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grid-space (not UV) position of a racket, sub-cell precision so movement
 * (especially the AI's) is smooth rather than snapping between texels.
 */
private data class RacketState(val x: Float, val y: Float)

/**
 * Owns the physics engine (Phase 1) and density extractor (Phase 2)
 * instances for the whole app lifetime, and drives them from a single
 * background game loop gated by [gameState]. All mutable game state is
 * exposed as [StateFlow]s so Compose can collect it directly.
 *
 * THREADING: the physics loop runs on [Dispatchers.Default]. Every
 * StateFlow it writes to is either (a) written ONLY from the loop
 * (countdownSeconds, playerScore, aiScore, aiRacketPosition) so there is
 * a single writer and no race, or (b) hopped onto
 * [Dispatchers.Main.immediate] for the write (gameState, collapseResult)
 * so it can never race with the UI-thread writes those same flows get
 * from pause/menu button clicks. playerRacketPosition is the mirror
 * image: written only from the UI thread (drag callbacks), read by the
 * loop -- StateFlow.value reads/writes are atomic, so no separate
 * synchronization is needed for either direction.
 */
class WaveCourtViewModel : ViewModel() {

    // -----------------------------------------------------------------
    // Physics engine + renderer-feed (Phase 1 / Phase 2), constructed once.
    // -----------------------------------------------------------------
    private val grid = Grid(width = GRID_SIZE, height = GRID_SIZE)
    private val solver = ADICrankNicolsonSolver(grid = grid, dt = SIM_DT, g = 0f)
    val densityExtractor = DensityExtractor(grid)

    val gridWidth: Int get() = grid.width
    val gridHeight: Int get() = grid.height

    // The net is purely visual (drawn by GameScreen's NetDivider) and
    // contributes NOTHING to solver.potential -- no barrier, no tunneling
    // effect to speak of since there's no barrier to tunnel through, no
    // reflection. netX itself is still very much alive as a logical
    // coordinate: see performCollapse() (scoring), DensityExtractor
    // (player/AI probability split), and updateAiRacket() (AI territory).

    // Reused scratch buffer for the Born-rule sample -- zero per-round allocation.
    private val densityScratch = FloatArray(grid.size)

    // -----------------------------------------------------------------
    // State machine
    // -----------------------------------------------------------------
    private val _gameState = MutableStateFlow(GameState.MENU)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _playerScore = MutableStateFlow(0)
    val playerScore: StateFlow<Int> = _playerScore.asStateFlow()

    private val _aiScore = MutableStateFlow(0)
    val aiScore: StateFlow<Int> = _aiScore.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(0f)
    val countdownSeconds: StateFlow<Float> = _countdownSeconds.asStateFlow()

    private val _collapseResult = MutableStateFlow<CollapseResult?>(null)
    val collapseResult: StateFlow<CollapseResult?> = _collapseResult.asStateFlow()

    private val _settings = MutableStateFlow(GameSettings())
    val settings: StateFlow<GameSettings> = _settings.asStateFlow()

    // Density-integration split, delegated straight from Phase 2 -- no
    // need to duplicate this bookkeeping in the ViewModel.
    val playerProbability: StateFlow<Float> get() = densityExtractor.playerProbability
    val aiProbability: StateFlow<Float> get() = densityExtractor.aiProbability

    private val _playerRacketPosition = MutableStateFlow<RacketStatePublic?>(null)
    val playerRacketPosition: StateFlow<RacketStatePublic?> = _playerRacketPosition.asStateFlow()

    private val _aiRacketPosition = MutableStateFlow<RacketStatePublic?>(null)
    val aiRacketPosition: StateFlow<RacketStatePublic?> = _aiRacketPosition.asStateFlow()

    private var physicsJob: Job? = null

    // -----------------------------------------------------------------
    // Menu -> ViewModel
    // -----------------------------------------------------------------

    /** Applied immediately: couplingG is written straight into the live solver. */
    fun updateSettings(transform: (GameSettings) -> GameSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        solver.g = next.couplingG
    }

    fun onStartGame() {
        _playerScore.value = 0
        _aiScore.value = 0
        initializeRound(_settings.value)
        _gameState.value = GameState.PLAYING
        startPhysicsLoop()
    }

    // -----------------------------------------------------------------
    // In-game controls
    // -----------------------------------------------------------------

    fun onPause() {
        if (_gameState.value == GameState.PLAYING) _gameState.value = GameState.PAUSED
    }

    fun onResume() {
        if (_gameState.value == GameState.PAUSED) _gameState.value = GameState.PLAYING
    }

    fun onQuitToMenu() {
        stopPhysicsLoop()
        _playerScore.value = 0
        _aiScore.value = 0
        _gameState.value = GameState.MENU
    }

    fun onNextRound() {
        val settings = _settings.value
        val matchWon = _playerScore.value >= settings.matchTargetScore ||
                _aiScore.value >= settings.matchTargetScore
        if (matchWon) {
            stopPhysicsLoop()
            _gameState.value = GameState.MATCH_OVER
        } else {
            initializeRound(settings)
            _gameState.value = GameState.PLAYING
        }
    }

    fun onPlayAgain() {
        // Loop is already stopped by the MATCH_OVER transition in
        // onNextRound(); this just resets score and drops back to the menu.
        _playerScore.value = 0
        _aiScore.value = 0
        _gameState.value = GameState.MENU
    }

    /**
     * Called from the game screen's drag handler with a position already
     * converted from screen UV into grid coordinates. Always excludes the
     * outermost hard-wall ring, which is meaningless to place a racket on.
     * In VS_AI, additionally clamped to the player's side of the net; in
     * NO_AI the player has the full grid to shape as a sandbox.
     */
    fun onPlayerRacketMove(gridX: Float, gridY: Float) {
        // NO_AI is a full-field sandbox: the player owns the whole grid, so
        // the racket is only clamped to stay off the hard-wall ring, not to
        // their side of the net. VS_AI keeps the original net restriction
        // so the player can't camp on the AI's side.
        val maxX = if (_settings.value.gameMode == GameMode.NO_AI) {
            (grid.width - 2).toFloat()
        } else {
            (densityExtractor.netX - 1).coerceAtLeast(1).toFloat()
        }
        val clampedX = gridX.coerceIn(1f, maxX)
        val clampedY = gridY.coerceIn(1f, (grid.height - 2).toFloat())
        _playerRacketPosition.value = RacketStatePublic(clampedX, clampedY)
    }

    fun onPlayerRacketRelease() {
        _playerRacketPosition.value = null
    }

    /**
     * "Clear the board": re-seeds the wavefunction to the currently chosen
     * initial state and drops any interference built up so far, without
     * touching playerScore/aiScore. Reuses initializeRound() verbatim --
     * that function already only clears grid/racket/collapse/countdown
     * state, never the score StateFlows -- so there's no separate reset
     * path to keep in sync. Safe to call from PAUSED or COLLAPSED since the
     * physics loop only *acts* on state == PLAYING; it does not require
     * PLAYING to already be set to keep running.
     */
    fun onResetWave() {
        initializeRound(_settings.value)
        _gameState.value = GameState.PLAYING
    }

    override fun onCleared() {
        stopPhysicsLoop()
        super.onCleared()
    }

    // -----------------------------------------------------------------
    // Round setup
    // -----------------------------------------------------------------

    private fun initializeRound(settings: GameSettings) {
        // Grid's own setters clear() internally, so no redundant clear here.
        when (settings.initialState) {
            InitialStateChoice.QUANTUM_VORTEX -> grid.setQuantumVortex()
            InitialStateChoice.SQUEEZED_STATE -> grid.setSqueezedState()
        }
        solver.g = settings.couplingG
        _playerRacketPosition.value = null
        _aiRacketPosition.value = null
        _collapseResult.value = null
        _countdownSeconds.value = when (settings.countdownMode) {
            CountdownMode.RANDOM -> Random.nextFloat() * (COUNTDOWN_RANDOM_MAX - COUNTDOWN_RANDOM_MIN) +
                    COUNTDOWN_RANDOM_MIN
            CountdownMode.FIXED -> settings.fixedCountdownSeconds
        }
    }

    // -----------------------------------------------------------------
    // Physics game loop
    // -----------------------------------------------------------------

    private fun startPhysicsLoop() {
        if (physicsJob?.isActive == true) return
        physicsJob = viewModelScope.launch(Dispatchers.Default) {
            var lastTimeNanos = System.nanoTime()
            while (isActive) {
                val now = System.nanoTime()
                // Clamp so a debugger pause / dropped frame can't inject a
                // huge countdown/AI-movement jump.
                val deltaSeconds = ((now - lastTimeNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastTimeNanos = now

                if (_gameState.value == GameState.PLAYING) {
                    rebuildPotential()
                    solver.step()
                    densityExtractor.extract()
                    if (_settings.value.gameMode == GameMode.VS_AI) {
                        updateAiRacket(deltaSeconds)
                    }
                    advanceCountdown(deltaSeconds)
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun stopPhysicsLoop() {
        physicsJob?.cancel()
        physicsJob = null
    }

    private suspend fun advanceCountdown(deltaSeconds: Float) {
        val remaining = (_countdownSeconds.value - deltaSeconds).coerceAtLeast(0f)
        _countdownSeconds.value = remaining
        if (remaining <= 0f) {
            val result = performCollapse()
            // Hop to Main so this can never race with a UI-thread gameState
            // write (e.g. a pause tap landing in the same instant).
            withContext(Dispatchers.Main.immediate) {
                _collapseResult.value = result
                _gameState.value = GameState.COLLAPSED
            }
        }
    }

    /**
     * potential[] is rebuilt from scratch every frame: zero it out (the net
     * no longer contributes anything physical -- see the comment by
     * densityExtractor above), then stamp in whichever rackets are
     * currently active. Cheap (O(grid size) fill) and avoids any
     * incremental-update bugs from rackets that moved or were released
     * since the last frame.
     */
    private fun rebuildPotential() {
        solver.potential.fill(0f)
        val sign = if (_settings.value.racketType == RacketType.ATTRACTIVE) -1f else 1f
        _playerRacketPosition.value?.let { applyRacketGaussian(solver.potential, it.x, it.y, sign) }
        if (_settings.value.gameMode == GameMode.VS_AI) {
            _aiRacketPosition.value?.let { applyRacketGaussian(solver.potential, it.x, it.y, sign) }
        }
    }

    private fun applyRacketGaussian(potential: FloatArray, cx: Float, cy: Float, sign: Float) {
        val minX = (cx - RACKET_WINDOW).toInt().coerceIn(1, grid.width - 2)
        val maxX = (cx + RACKET_WINDOW).toInt().coerceIn(1, grid.width - 2)
        val minY = (cy - RACKET_WINDOW).toInt().coerceIn(1, grid.height - 2)
        val maxY = (cy + RACKET_WINDOW).toInt().coerceIn(1, grid.height - 2)
        val twoSigmaSq = 2f * RACKET_SIGMA * RACKET_SIGMA
        for (y in minY..maxY) {
            val dy = y - cy
            val row = y * grid.width
            for (x in minX..maxX) {
                val dx = x - cx
                val gaussian = exp(-(dx * dx + dy * dy) / twoSigmaSq)
                potential[row + x] += sign * RACKET_AMPLITUDE * gaussian
            }
        }
    }

    /**
     * Moves the AI racket toward the highest-density cell on the AI's OWN
     * side (x in [netX, width)) -- the AI plays defense, trying to hit the
     * wave back toward the player before it collapses on its own side, at
     * a capped speed. "Medium difficulty" is this fixed speed/no-noise
     * combination: it always knows exactly where the peak on its side is
     * (perfect tracking accuracy) but can only close the distance at
     * AI_SPEED grid-units/sec (bounded reaction speed).
     */
    private fun updateAiRacket(deltaSeconds: Float) {
        val netX = densityExtractor.netX
        val re = grid.re
        val im = grid.im
        var peakDensity = -1f
        var peakX = ((netX + grid.width) / 2).coerceAtMost(grid.width - 2)
        var peakY = grid.height / 2
        for (y in 1 until grid.height - 1) {
            val row = y * grid.width
            for (x in netX until grid.width - 1) {
                val i = row + x
                val d = re[i] * re[i] + im[i] * im[i]
                if (d > peakDensity) {
                    peakDensity = d
                    peakX = x
                    peakY = y
                }
            }
        }

        val targetX = peakX.toFloat()
        val targetY = peakY.toFloat()
        val current = _aiRacketPosition.value ?: RacketStatePublic(targetX, targetY)
        val dx = targetX - current.x
        val dy = targetY - current.y
        val dist = sqrt(dx * dx + dy * dy)
        val maxStep = AI_SPEED * deltaSeconds

        _aiRacketPosition.value = if (dist <= maxStep || dist == 0f) {
            RacketStatePublic(targetX, targetY)
        } else {
            RacketStatePublic(current.x + dx / dist * maxStep, current.y + dy / dist * maxStep)
        }
    }

    /**
     * Born-rule measurement: samples a grid cell with probability
     * proportional to |psi|^2 there, then scores the point. If the
     * collapse lands on the player's side (x < netX) the player failed to
     * return it and the AI scores, and vice versa -- mirroring "you lose a
     * point if it collapses on your side."
     */
    private fun performCollapse(): CollapseResult {
        grid.probabilityDensity(densityScratch)
        var total = 0.0
        for (d in densityScratch) total += d

        val idx = if (total <= 0.0) {
            densityScratch.size / 2 // degenerate all-zero state; shouldn't happen given normalize()
        } else {
            val r = Random.nextDouble() * total
            var cumulative = 0.0
            var chosen = densityScratch.size - 1
            for (i in densityScratch.indices) {
                cumulative += densityScratch[i]
                if (cumulative >= r) {
                    chosen = i
                    break
                }
            }
            chosen
        }

        val x = idx % grid.width
        val y = idx / grid.width
        val winner = if (x < densityExtractor.netX) Winner.AI else Winner.PLAYER
        if (winner == Winner.AI) _aiScore.value += 1 else _playerScore.value += 1
        return CollapseResult(x, y, grid.width, grid.height, winner)
    }

    companion object {
        private const val GRID_SIZE = 128
        private const val SIM_DT = 0.4f

        private const val RACKET_SIGMA = 6f
        private const val RACKET_AMPLITUDE = 10f
        private const val RACKET_WINDOW = 4f * RACKET_SIGMA

        private const val AI_SPEED = 40f // grid units / second
        private const val FRAME_INTERVAL_MS = 16L

        private const val COUNTDOWN_RANDOM_MIN = 6f
        private const val COUNTDOWN_RANDOM_MAX = 12f
    }
}

/**
 * Public (grid-space) racket position exposed to the UI layer. Kept
 * distinct from the private RacketState used internally purely so the UI
 * module never needs a dependency on ViewModel-private types.
 */
data class RacketStatePublic(val x: Float, val y: Float)