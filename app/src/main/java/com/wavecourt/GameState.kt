package com.wavecourt

import com.wavecourt.render.RacketOverlayMode

/**
 * Top-level game state machine.
 *
 *   MENU        -> physics loop stopped, menu UI shown
 *   PLAYING     -> physics loop running, countdown decrementing, rackets live
 *   COLLAPSED   -> physics loop paused (loop stays alive but does no work),
 *                  collapse flash + "Next Round" shown
 *   PAUSED      -> physics loop paused, countdown frozen, Resume/Quit shown
 *   MATCH_OVER  -> physics loop stopped, winner banner + "Play Again" shown
 *
 * Transitions are owned entirely by WaveCourtViewModel; this file only
 * defines the vocabulary.
 */
enum class GameState { MENU, PLAYING, COLLAPSED, PAUSED, MATCH_OVER }

/** "vs AI": AI tracks the player. "No AI": AI never places a racket. */
enum class GameMode { VS_AI, NO_AI }

/** Which closed-form initial wavefunction (see Grid.kt) a match starts from. */
enum class InitialStateChoice { QUANTUM_VORTEX, SQUEEZED_STATE }

/** Whether the per-round countdown is randomized or a fixed, user-set value. */
enum class CountdownMode { RANDOM, FIXED }

/**
 * Sign convention for racket potentials: ATTRACTIVE = negative V (a
 * potential well the wave gets pulled into), REPULSIVE = positive V (a
 * barrier the wave is pushed away from). Applies to both player and AI
 * rackets -- the menu exposes a single global toggle, not one per side.
 */
enum class RacketType { ATTRACTIVE, REPULSIVE }

/** Who a given round's Born-rule collapse awarded the point to. */
enum class Winner { PLAYER, AI }

/**
 * Result of one measurement event: where on the grid the wavefunction
 * collapsed, expressed in flat grid coordinates (not normalized UV -- the
 * UI converts using gridWidth/gridHeight, which travel with the result so
 * the conversion is unambiguous even if the grid size ever changes).
 */
data class CollapseResult(
    val gridX: Int,
    val gridY: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val winner: Winner
)

/**
 * All menu-configurable knobs, bundled so the menu screen can hold and
 * mutate a single piece of state. `couplingG`, `racketType`, and
 * `racketOverlayMode` are read live by the physics loop / renderer every
 * frame, so changes apply immediately with no restart -- `gameMode`,
 * `initialState`, `countdownMode`, `fixedCountdownSeconds`, and
 * `matchTargetScore` are only consulted at round/match boundaries
 * (onStartGame / onNextRound), matching how a real countdown or win
 * condition should behave if changed mid-round.
 */
data class GameSettings(
    val gameMode: GameMode = GameMode.VS_AI,
    val initialState: InitialStateChoice = InitialStateChoice.QUANTUM_VORTEX,
    val countdownMode: CountdownMode = CountdownMode.RANDOM,
    val fixedCountdownSeconds: Float = 10f,
    val matchTargetScore: Int = 3,
    val couplingG: Float = 0f,
    val racketType: RacketType = RacketType.ATTRACTIVE,
    val racketOverlayMode: RacketOverlayMode = RacketOverlayMode.VISIBLE
)