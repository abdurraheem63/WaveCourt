package com.wavecourt.physics

/**
 * Complex tridiagonal ("Thomas algorithm") solver specialized for the case
 * where the sub-diagonal / diagonal / super-diagonal coefficients (a, b, c)
 * are the SAME complex constant for every row or column being solved --
 * which is exactly what happens in the ADI-CN kinetic sub-steps, since
 * those coefficients depend only on (dt, dx, dy), never on space or on
 * psi itself (the space-varying potential is handled separately, as an
 * exact phase rotation, in ADICrankNicolsonSolver).
 *
 * Because a, b, c never change between calls, the forward-elimination
 * multipliers are precomputed ONCE in the constructor and reused for
 * every row/column, every frame. Each solve() call then does only the
 * O(m) RHS forward pass + back-substitution -- no divisions and no
 * per-call heap allocation (all arithmetic is inlined complex math on
 * primitive Floats; no boxing).
 *
 * System solved for interior unknowns x[1..n-2] (m = n - 2 of them):
 *   a*x[i-1] + b*x[i] + c*x[i+1] = d[i]
 * with Dirichlet boundary conditions x[0] = x[n-1] = 0 (hard walls).
 */
class TridiagonalSolver(
    private val n: Int,
    private val aRe: Float, private val aIm: Float,
    bRe: Float, bIm: Float,
    private val cRe: Float, private val cIm: Float
) {
    private val m = n - 2

    private val cPrimeRe = FloatArray(m)
    private val cPrimeIm = FloatArray(m)
    private val invDenomRe = FloatArray(m)
    private val invDenomIm = FloatArray(m)

    // Reused scratch buffer for the forward-substituted RHS (d'). Solves on
    // a given axis (all rows, then all columns) happen sequentially on the
    // calling thread within one kinetic half-step, so a single shared
    // scratch buffer per solver instance is safe.
    private val dPrimeRe = FloatArray(m)
    private val dPrimeIm = FloatArray(m)

    init {
        require(m >= 1) { "Grid line too short for interior tridiagonal solve" }

        // i = 0: denom = b
        var denomRe = bRe
        var denomIm = bIm
        var denomSq = denomRe * denomRe + denomIm * denomIm
        var invRe = denomRe / denomSq
        var invIm = -denomIm / denomSq
        invDenomRe[0] = invRe
        invDenomIm[0] = invIm
        cPrimeRe[0] = cRe * invRe - cIm * invIm
        cPrimeIm[0] = cRe * invIm + cIm * invRe

        for (i in 1 until m) {
            // a * c'_{i-1}
            val acRe = aRe * cPrimeRe[i - 1] - aIm * cPrimeIm[i - 1]
            val acIm = aRe * cPrimeIm[i - 1] + aIm * cPrimeRe[i - 1]
            denomRe = bRe - acRe
            denomIm = bIm - acIm
            denomSq = denomRe * denomRe + denomIm * denomIm
            invRe = denomRe / denomSq
            invIm = -denomIm / denomSq
            invDenomRe[i] = invRe
            invDenomIm[i] = invIm

            cPrimeRe[i] = cRe * invRe - cIm * invIm
            cPrimeIm[i] = cRe * invIm + cIm * invRe
        }
    }

    /**
     * Solves the tridiagonal system for one line of the grid, reading the
     * RHS from and writing the solution directly into flat Grid-style
     * arrays -- no temporary line-buffer copy.
     *
     * @param offset  flat index of the line's FIRST point (a boundary
     *                point, always solved to 0)
     * @param stride  flat-index step between consecutive points on the
     *                line (1 for an x-sweep row, `width` for a y-sweep
     *                column)
     *
     * Caller contract: only invoke this for INTERIOR lines (i.e. never for
     * the grid's own boundary rows/columns) -- those must stay identically
     * zero and are never touched by this solver.
     */
    fun solve(
        dRe: FloatArray, dIm: FloatArray,
        xRe: FloatArray, xIm: FloatArray,
        offset: Int, stride: Int
    ) {
        // Forward pass: i = 0
        var di = offset + stride
        var vRe = dRe[di] * invDenomRe[0] - dIm[di] * invDenomIm[0]
        var vIm = dRe[di] * invDenomIm[0] + dIm[di] * invDenomRe[0]
        dPrimeRe[0] = vRe
        dPrimeIm[0] = vIm

        for (i in 1 until m) {
            di = offset + (i + 1) * stride
            val adRe = aRe * dPrimeRe[i - 1] - aIm * dPrimeIm[i - 1]
            val adIm = aRe * dPrimeIm[i - 1] + aIm * dPrimeRe[i - 1]
            val numRe = dRe[di] - adRe
            val numIm = dIm[di] - adIm
            vRe = numRe * invDenomRe[i] - numIm * invDenomIm[i]
            vIm = numRe * invDenomIm[i] + numIm * invDenomRe[i]
            dPrimeRe[i] = vRe
            dPrimeIm[i] = vIm
        }

        // Back substitution
        var xNextRe = dPrimeRe[m - 1]
        var xNextIm = dPrimeIm[m - 1]
        xRe[offset + m * stride] = xNextRe
        xIm[offset + m * stride] = xNextIm

        for (i in m - 2 downTo 0) {
            val cxRe = cPrimeRe[i] * xNextRe - cPrimeIm[i] * xNextIm
            val cxIm = cPrimeRe[i] * xNextIm + cPrimeIm[i] * xNextRe
            val vr = dPrimeRe[i] - cxRe
            val vi = dPrimeIm[i] - cxIm
            xRe[offset + (i + 1) * stride] = vr
            xIm[offset + (i + 1) * stride] = vi
            xNextRe = vr
            xNextIm = vi
        }

        // Dirichlet hard-wall boundary points for this line.
        xRe[offset] = 0f
        xIm[offset] = 0f
        xRe[offset + (n - 1) * stride] = 0f
        xIm[offset + (n - 1) * stride] = 0f
    }
}