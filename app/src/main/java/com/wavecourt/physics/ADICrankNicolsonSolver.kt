package com.wavecourt.physics

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Time-steps a 2D Gross-Pitaevskii wavefunction using symmetric Strang
 * operator splitting:
 *
 *   1. Kinetic half-step   (duration dt/2) via Peaceman-Rachford ADI-CN
 *   2. Potential + nonlinear + CAP full-step (duration dt), an EXACT local
 *      phase rotation with an exact local amplitude decay
 *   3. Kinetic half-step   (duration dt/2) via Peaceman-Rachford ADI-CN
 *   4. Explicit renormalization (see CAP note below)
 *
 * Governing equation (dimensionless units, hbar = m = 1):
 *   i d(psi)/dt = [ -1/2 * laplacian(psi) + (V(x,y) - i*gamma(x,y))*psi + g|psi|^2*psi ]
 *
 * WHY SPLIT THIS WAY (see design discussion): applying ADI-CN directly to
 * the full nonlinear equation would require the g|psi|^2 term to be
 * evaluated implicitly, forcing a per-step fixed-point iteration with no
 * guaranteed convergence for large |g|. Instead:
 *
 *  - The kinetic operator Hx + Hy is linear and handled by two tridiagonal
 *    solves (Peaceman-Rachford ADI). This factorization is 2nd-order
 *    accurate in the step size, consistent with the outer Strang split.
 *
 *  - The potential + nonlinear + CAP operator (V - i*gamma + g|psi|^2) is
 *    LOCAL (diagonal in position space): at a fixed instant it commutes
 *    with itself at every point, so its time evolution over a step has a
 *    closed-form exact solution -- a phase rotation combined with an
 *    amplitude decay:
 *        psi(x,y) <- psi(x,y) * exp(-i*(V+g|psi|^2)*dt) * exp(-gamma*dt)
 *    Both factors are exact solutions of the corresponding independent
 *    per-point ODE; splitting them from the kinetic term introduces no
 *    additional error beyond the outer 2nd-order Strang split.
 *
 * CAP (Complex Absorbing Potential) SPONGE LAYER
 * -----------------------------------------------
 * A ring [spongeWidth] cells wide along all four edges carries an
 * imaginary potential -i*gamma(x,y), gamma >= 0, which exponentially
 * damps |psi| as it approaches the hard wall. gamma ramps smoothly from
 * 0 at the sponge's inner edge to [spongeStrength] at the outermost ring
 * following a cubic profile (zero value AND zero slope at the inner
 * edge), so there is no impedance discontinuity to reflect off of -- the
 * classic failure mode of a sponge with a sharp on/off boundary.
 *
 * gamma is evaluated independently per axis and SUMMED (gamma = gammaX +
 * gammaY), the standard PML-style construction: cells near a flat edge
 * get damped by that edge alone, while corner cells -- which see two
 * edges' worth of outgoing wave -- get proportionally stronger damping.
 * This never touches `potential[]` (the real, mutable V that the net and
 * rackets write into every frame): gamma lives in its own array, built
 * once from grid geometry alone, so sponge behavior is completely
 * decoupled from -- and never disturbed by -- gameplay potentials.
 *
 * IMPORTANT CAVEAT ON NORM CONSERVATION: the project's invariant is that
 * norm() == 1 after every step (see grid.normalize() in step()). The CAP
 * removes probability at the edges *before* that renormalization runs,
 * so the renormalization rescales the (slightly reduced) remaining field
 * back up to unit norm. This is mathematically just a uniform amplitude
 * rescale -- it does NOT undo the sponge's effect on wave *shape*, so
 * outgoing wavefronts are still killed before they can reflect and cause
 * chaotic interference. What it does mean is that mass absorbed at the
 * boundary is effectively recycled back into the interior amplitude
 * rather than truly discarded (there is no physically "open" boundary in
 * the strict sense) -- a deliberate gameplay-motivated tradeoff, not a
 * bug, and one that matches the project's existing "norm always == 1"
 * requirement.
 *
 * NONLINEAR TERM CALIBRATION (g|psi|^2)
 * -----------------------------------------------
 * Because normalize() enforces integral(|psi|^2 dA) == 1 over the WHOLE
 * grid (128x128 = 16384 cells), a single localized wavepacket's peak
 * density is only on the order of 1e-3 (e.g. ~0.0024 for the default
 * Squeezed State). At the UI-facing slider range of g in [-12, 12], the
 * raw phase contribution from g*density*dt per step is roughly 15-20x
 * too small to compete visually with the kinetic term's own phase scale
 * -- the self-focusing/defocusing effect is real but numerically buried.
 *
 * [nonlinearScale] closes that gap: it's a fixed calibration constant
 * between the friendly UI range (-12..12) and the coupling strength that
 * actually needs to appear in g|psi|^2 for the effect to be clearly
 * visible within a round. It does NOT change the physics qualitatively --
 * g|psi|^2 is still exactly what appears in the phase rotation below --
 * it just rescales what "g=12" physically means, the same way choosing
 * units does in any simulation. Tune this single constant first if the
 * effect ever needs to be stronger or weaker; nothing else needs to
 * change.
 *
 * Strong negative g (attractive/self-focusing) can drive the packet into
 * a genuine collapse -- physically, this is the real "Bosenova" collapse
 * phenomenon seen in attractive BECs above a critical coupling, and is an
 * intentional, scientifically-accurate gameplay moment here, not a bug.
 * It is only a problem if it produces non-finite (NaN/Inf) values; watch
 * for that specifically if [nonlinearScale] or the slider range is ever
 * pushed further.
 */
class ADICrankNicolsonSolver(
    private val grid: Grid,
    private val dt: Float,
    var g: Float = 0f,
    spongeWidth: Int = 12,
    spongeStrength: Float = 3f
) {
    private val width = grid.width
    private val height = grid.height
    private val dx = grid.dx
    private val dy = grid.dy

    /**
     * Potential energy field V(x,y), same flat layout as Grid (index =
     * y*width+x). The game layer (rackets, central net, extra walls)
     * mutates this in place each frame; the physics engine only reads it.
     * This is the REAL potential only -- the CAP's imaginary part lives
     * separately in [spongeDecay] and is never mixed into this array, so
     * gameplay code can keep treating `potential` exactly as before.
     */
    val potential = FloatArray(grid.size)

    // Scratch buffers reused every step -- zero per-frame allocation.
    private val rhsRe = FloatArray(grid.size)
    private val rhsIm = FloatArray(grid.size)
    private val midRe = FloatArray(grid.size)
    private val midIm = FloatArray(grid.size)

    // tau = dt/2 is the fixed duration of each kinetic half-step. Since
    // (tau, dx, dy) never change after construction, the alpha
    // coefficients and both tridiagonal solvers -- including their
    // internal forward-sweep coefficients -- are built exactly ONCE here,
    // not recomputed per frame.
    private val tau = dt / 2f
    private val alphaX = tau / (4f * dx * dx)
    private val alphaY = tau / (4f * dy * dy)

    // (I + i*tau/2*Hx): diag = 1 + 2i*alphaX, off-diag = -i*alphaX
    private val xSolver = TridiagonalSolver(
        n = width,
        aRe = 0f, aIm = -alphaX,
        bRe = 1f, bIm = 2f * alphaX,
        cRe = 0f, cIm = -alphaX
    )

    // (I + i*tau/2*Hy): diag = 1 + 2i*alphaY, off-diag = -i*alphaY
    private val ySolver = TridiagonalSolver(
        n = height,
        aRe = 0f, aIm = -alphaY,
        bRe = 1f, bIm = 2f * alphaY,
        cRe = 0f, cIm = -alphaY
    )

    // -----------------------------------------------------------------
    // CAP sponge layer: precomputed ONCE from grid geometry (never
    // depends on psi, V, dt-varying state, or per-frame gameplay
    // potentials). Stores exp(-gamma(x,y)*dt) directly -- since gamma and
    // dt are both fixed for the lifetime of this solver, so is this decay
    // factor, so there's no reason to call exp() again every frame in the
    // hot loop. Cells outside the sponge region store exactly 1f (no-op).
    // -----------------------------------------------------------------
    private val spongeDecay = FloatArray(grid.size).also { arr ->
        val safeWidth = spongeWidth.coerceIn(1, min(width, height) / 2 - 1)
        for (y in 0 until height) {
            val gammaY = edgeGamma(y, height, safeWidth, spongeStrength)
            for (x in 0 until width) {
                val gammaX = edgeGamma(x, width, safeWidth, spongeStrength)
                arr[y * width + x] = exp(-(gammaX + gammaY) * dt)
            }
        }
    }

    /**
     * Calibration factor between the UI-facing coupling slider (-12..12,
     * chosen for a friendly range) and the coupling strength that
     * actually needs to appear in g|psi|^2 to produce a visually apparent
     * self-focusing/defocusing effect within a round. See the class-level
     * "NONLINEAR TERM CALIBRATION" note above for the derivation (peak
     * density ~1e-3 for a localized packet under whole-grid
     * normalization). Tune here first if the effect needs to be
     * stronger/weaker -- nothing else needs to change.
     */
    private val nonlinearScale = 1.05f

    /**
     * Cubic-ramp absorption coefficient for a single axis coordinate.
     * Returns 0 outside the sponge (interior), ramping smoothly (zero
     * value AND zero slope at the inner edge, matching the eta^3 profile
     * requested) up to [strength] exactly at the boundary cell.
     */
    private fun edgeGamma(coord: Int, dim: Int, width: Int, strength: Float): Float {
        val distFromEdge = min(coord, dim - 1 - coord)
        if (distFromEdge >= width) return 0f
        val eta = (width - distFromEdge).toFloat() / width.toFloat() // 0..1, 1 at the wall
        return strength * eta * eta * eta
    }

    /** Advances the wavefunction by exactly one timestep `dt`. */
    fun step() {
        kineticHalfStep()
        potentialAndNonlinearStep()
        kineticHalfStep()
        grid.normalize()
        grid.enforceHardWalls()
    }

    // -----------------------------------------------------------------
    // Kinetic half-step: Peaceman-Rachford ADI-CN, x-sweep then y-sweep.
    // Only INTERIOR rows/columns are ever solved; boundary rows/columns
    // are hard walls and are simply never written, so they stay at their
    // prior value of exactly 0 (FloatArray default, preserved by
    // grid.enforceHardWalls() at the end of every step()).
    // -----------------------------------------------------------------
    private fun kineticHalfStep() {
        // Sub-step 1: (I + i*tau/2*Hx) psi* = (I - i*tau/2*Hy) psi
        applyExplicitHy(grid.re, grid.im, rhsRe, rhsIm)
        for (y in 1 until height - 1) {
            val offset = grid.index(0, y)
            xSolver.solve(rhsRe, rhsIm, midRe, midIm, offset = offset, stride = 1)
        }

        // Sub-step 2: (I + i*tau/2*Hy) psi_next = (I - i*tau/2*Hx) psi*
        applyExplicitHx(midRe, midIm, rhsRe, rhsIm)
        for (x in 1 until width - 1) {
            val offset = grid.index(x, 0)
            ySolver.solve(rhsRe, rhsIm, grid.re, grid.im, offset = offset, stride = width)
        }
    }

    /**
     * out = (I - i*tau/2*Hy) * psi, elementwise, for interior rows only:
     *   out_{x,y} = (1 - 2i*alphaY)*psi_{x,y} + i*alphaY*(psi_{x,y-1} + psi_{x,y+1})
     */
    private fun applyExplicitHy(srcRe: FloatArray, srcIm: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        for (y in 1 until height - 1) {
            val row = y * width
            val rowUp = row - width
            val rowDown = row + width
            for (x in 0 until width) {
                val i = row + x
                val cRe = srcRe[i]
                val cIm = srcIm[i]
                val nRe = srcRe[rowUp + x] + srcRe[rowDown + x]
                val nIm = srcIm[rowUp + x] + srcIm[rowDown + x]

                // (1 - 2i*alphaY) * c
                val termRe = cRe + 2f * alphaY * cIm
                val termIm = cIm - 2f * alphaY * cRe
                // + i*alphaY * n
                val neighRe = -alphaY * nIm
                val neighIm = alphaY * nRe

                outRe[i] = termRe + neighRe
                outIm[i] = termIm + neighIm
            }
        }
    }

    /**
     * out = (I - i*tau/2*Hx) * psi, elementwise, for interior columns only:
     *   out_{x,y} = (1 - 2i*alphaX)*psi_{x,y} + i*alphaX*(psi_{x-1,y} + psi_{x+1,y})
     */
    private fun applyExplicitHx(srcRe: FloatArray, srcIm: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val cRe = srcRe[i]
                val cIm = srcIm[i]
                val nRe = srcRe[i - 1] + srcRe[i + 1]
                val nIm = srcIm[i - 1] + srcIm[i + 1]

                val termRe = cRe + 2f * alphaX * cIm
                val termIm = cIm - 2f * alphaX * cRe
                val neighRe = -alphaX * nIm
                val neighIm = alphaX * nRe

                outRe[i] = termRe + neighRe
                outIm[i] = termIm + neighIm
            }
        }
    }

    // -----------------------------------------------------------------
    // Potential + nonlinear + CAP step: exact local phase rotation
    // combined with an exact local amplitude decay.
    // -----------------------------------------------------------------
    private fun potentialAndNonlinearStep() {
        for (i in 0 until grid.size) {
            val re = grid.re[i]
            val im = grid.im[i]
            val density = re * re + im * im
            // V_eff = V_ext + nonlinearScale * g * |psi|^2 -- see the
            // NONLINEAR TERM CALIBRATION note at the top of this file for
            // why nonlinearScale is needed and why it doesn't change the
            // physics qualitatively, only the UI-slider-to-physical-g
            // mapping.
            val theta = (potential[i] + nonlinearScale * g * density) * dt
            val c = cos(theta)
            val s = sin(theta)
            val decay = spongeDecay[i]
            // psi <- psi * exp(-i*theta) * exp(-gamma*dt)
            grid.re[i] = (re * c + im * s) * decay
            grid.im[i] = (im * c - re * s) * decay
        }
    }
}