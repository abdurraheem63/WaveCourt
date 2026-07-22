package com.wavecourt.render

import com.wavecourt.physics.Grid
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Turns the physics engine's raw wavefunction into display-ready data,
 * without ever touching the UI thread for the heavy work.
 *
 * Produces two things every time [extract] is called:
 *  1. A display-normalized `|psi|^2` frame in `[0, 1]`, published through a
 *     lock-free double buffer ([latestFrame]) plus a [frameTick] doorbell
 *     so Compose only redraws when a new frame actually exists.
 *  2. The integrated probability on each side of the net ([playerProbability],
 *     [aiProbability]) for the score bars.
 *
 * THREADING CONTRACT:
 *  - [extract] must be invoked from the physics loop, once per completed
 *    `solver.step()`, and never concurrently with another `extract()` call
 *    or with `solver.step()` itself (both touch `grid.re` / `grid.im`).
 *    Phase 3's physics loop owns this sequencing; this class does not
 *    synchronize against the physics engine itself.
 *  - Everything the UI thread reads ([latestFrame], [frameTick],
 *    [playerProbability], [aiProbability]) is safe to read from Compose at
 *    any time, from any thread, without blocking: `AtomicReference.get()`
 *    and `StateFlow.value` are both non-blocking reads, and the buffer a
 *    reference points to is never mutated after publication -- only
 *    replaced.
 */
class DensityExtractor(private val grid: Grid) {

    val gridWidth: Int get() = grid.width
    val gridHeight: Int get() = grid.height

    /**
     * Grid-cell x-coordinate of the net. Cells with x < netX are the
     * player's side; x >= netX is the AI's side. Defaults to the grid's
     * vertical centerline; Phase 3 can update this if the net ever moves.
     */
    var netX: Int = grid.width / 2

    // Ping-pong buffers: extract() always writes into whichever buffer is
    // NOT currently published, then does a single atomic reference swap.
    // The UI thread therefore only ever sees a fully-written array -- no
    // tearing is possible, and no allocation happens per frame.
    private val bufferA = FloatArray(grid.size)
    private val bufferB = FloatArray(grid.size)
    private val published = AtomicReference(bufferA)

    /** Latest fully-written, display-normalized (0..1) density frame. */
    val latestFrame: FloatArray
        get() = published.get()

    private val _frameTick = MutableStateFlow(0L)

    /** Increments by 1 every time a new frame is published. Doorbell for Compose. */
    val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    private val _playerProbability = MutableStateFlow(0.5f)
    val playerProbability: StateFlow<Float> = _playerProbability.asStateFlow()

    private val _aiProbability = MutableStateFlow(0.5f)
    val aiProbability: StateFlow<Float> = _aiProbability.asStateFlow()

    /**
     * Computes `|psi|^2` per cell from the current grid state, publishes a
     * display-normalized copy, and updates the player/AI probability
     * split. Always runs on [Dispatchers.Default].
     *
     * Display normalization divides by this frame's peak density so the
     * shader always gets values spanning the full [0, 1] range -- this is
     * independent of, and does not affect, the physics engine's own norm
     * conservation (`grid.norm() == 1`), which is enforced separately by
     * `Grid.normalize()` after every physics step.
     */
    suspend fun extract() = withContext(Dispatchers.Default) {
        val current = published.get()
        val back = if (current === bufferA) bufferB else bufferA

        val re = grid.re
        val im = grid.im
        val width = grid.width
        val height = grid.height

        var maxDensity = 0f
        var leftSum = 0.0
        var rightSum = 0.0
        val net = netX

        // Single pass: raw |psi|^2 into `back`, track the frame's peak for
        // display scaling, and accumulate the player/AI split. Iterated by
        // row/column (not flat index + modulo) to know each cell's x
        // without a division, matching Grid's own row-major layout.
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                val i = rowStart + x
                val r = re[i]
                val v = im[i]
                val density = r * r + v * v

                back[i] = density
                if (density > maxDensity) maxDensity = density

                if (x < net) leftSum += density else rightSum += density
            }
        }

        // Second pass: rescale into [0, 1] for the shader. Guard against an
        // all-zero frame (e.g. before any initial state has been set).
        val scale = if (maxDensity > 0f) 1f / maxDensity else 0f
        for (i in back.indices) {
            back[i] *= scale
        }

        published.set(back)
        _frameTick.value += 1

        // dx*dy would appear identically in both leftSum and rightSum, so it
        // cancels out of the ratio -- omitted here since only the split
        // matters for the score bars, not the absolute integral.
        val total = leftSum + rightSum
        if (total > 0.0) {
            _playerProbability.value = (leftSum / total).toFloat()
            _aiProbability.value = (rightSum / total).toFloat()
        }
    }
}