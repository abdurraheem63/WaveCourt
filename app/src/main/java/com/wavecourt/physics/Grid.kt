package com.wavecourt.physics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Flat 1D representation of a 2D complex-valued wavefunction psi(x,y) on a
 * power-of-two grid. Two separate FloatArrays hold the real and imaginary
 * parts (re[], im[]) rather than an interleaved array -- simpler indexing,
 * easier debugging, and the performance delta is negligible at 128x128 /
 * 256x256 grid sizes.
 *
 * Layout: index(x, y) = y * width + x   (row-major, x fastest-varying).
 */
class Grid(
    val width: Int,
    val height: Int,
    val dx: Float = 1f,
    val dy: Float = 1f
) {
    init {
        require(width > 0 && (width and (width - 1)) == 0) { "width must be a power of 2" }
        require(height > 0 && (height and (height - 1)) == 0) { "height must be a power of 2" }
    }

    val size = width * height

    /** Real part of psi at each flat index. */
    val re = FloatArray(size)

    /** Imaginary part of psi at each flat index. */
    val im = FloatArray(size)

    /** Flat index for grid coordinates (x, y). */
    fun index(x: Int, y: Int): Int = y * width + x

    // ---------------------------------------------------------------
    // Diagnostics / invariants
    // ---------------------------------------------------------------

    /**
     * Probability density |psi|^2 at every grid point, written into `out`
     * (reused by the caller each frame to avoid per-frame allocation when
     * feeding the render texture).
     */
    fun probabilityDensity(out: FloatArray = FloatArray(size)): FloatArray {
        for (i in 0 until size) {
            out[i] = re[i] * re[i] + im[i] * im[i]
        }
        return out
    }

    /**
     * Total probability integral(|psi|^2 dA) = sum_i |psi_i|^2 * dx * dy.
     * Must equal 1.0 for a physically valid state. Accumulated in Double
     * to avoid float summation drift masking real norm loss.
     */
    fun norm(): Float {
        var sum = 0.0
        for (i in 0 until size) {
            val d = re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            sum += d
        }
        return (sum * dx * dy).toFloat()
    }

    /**
     * Rescales psi so norm() == 1 exactly (up to float precision). Called
     * after every physics step. Because the Strang-split ADI-CN scheme is
     * unitary by construction, this corrects only floating-point drift --
     * it is a safety net, not a crutch for a lossy integrator.
     */
    fun normalize() {
        val n = norm()
        if (n <= 0f || n.isNaN()) return
        val scale = 1f / sqrt(n)
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    /** Zeroes psi everywhere; called before writing a fresh initial state. */
    fun clear() {
        re.fill(0f)
        im.fill(0f)
    }

    /** Explicitly forces psi = 0 on the outermost ring (hard-wall Dirichlet BC). */
    fun enforceHardWalls() {
        for (x in 0 until width) {
            val top = index(x, 0)
            val bottom = index(x, height - 1)
            re[top] = 0f; im[top] = 0f
            re[bottom] = 0f; im[bottom] = 0f
        }
        for (y in 0 until height) {
            val left = index(0, y)
            val right = index(width - 1, y)
            re[left] = 0f; im[left] = 0f
            re[right] = 0f; im[right] = 0f
        }
    }

    // ---------------------------------------------------------------
    // Initial states
    // ---------------------------------------------------------------

    /**
     * Single Gaussian wavepacket with unequal widths along x and y (an
     * elliptical "squeezed" state), carrying momentum k0 along x so it
     * drifts across the court instead of sitting still:
     *
     *   psi = A * exp(-(x-cx)^2/(4 sigma_x^2) - (y-cy)^2/(4 sigma_y^2))
     *           * exp(i k0 (x-cx))
     *
     * sigmaX != sigmaY means the packet is compressed along one axis and
     * elongated along the other. Overall normalization is handled by
     * normalize() at the end, so the amplitude prefactor A doesn't need
     * to be tracked separately.
     *
     * HONEST CAVEAT ON "BREATHING": a textbook squeezed state breathes
     * periodically only when it sits in a harmonic trap -- the restoring
     * force is what turns anisotropic spreading into an oscillation. This
     * grid has hard walls, not a harmonic potential, so what actually
     * happens here is standard anisotropic free-particle dispersion: the
     * narrower axis (higher momentum-space spread, by the uncertainty
     * relation) widens faster than the broader axis, so the packet's
     * aspect ratio visibly changes shape as it evolves and travels. Once
     * it reaches a wall/sponge and starts interacting with its own
     * reflections, the interference becomes genuinely dynamic and
     * pond-ripple-like rather than a clean periodic breathing cycle. If
     * true periodic breathing is wanted later, that requires adding a
     * real harmonic term to `potential[]` (e.g. a shallow parabolic well
     * centered on the court) -- happy to add that as a separate initial
     * state if useful.
     */
    fun setSqueezedState(
        sigmaXFrac: Float = 0.045f,
        sigmaYFrac: Float = 0.09f,
        k0: Float = 0.5f
    ) {
        clear()
        val cx = width / 2f
        val cy = height / 2f
        val sigmaX = width * sigmaXFrac
        val sigmaY = height * sigmaYFrac
        val twoSigmaXSq = 2f * sigmaX * sigmaX
        val twoSigmaYSq = 2f * sigmaY * sigmaY

        for (y in 0 until height) {
            for (x in 0 until width) {
                val fx = x.toFloat()
                val fy = y.toFloat()
                val dxp = fx - cx
                val dyp = fy - cy

                val envelope = exp(-(dxp * dxp) / twoSigmaXSq - (dyp * dyp) / twoSigmaYSq)
                val phase = k0 * dxp

                val i = index(x, y)
                re[i] = envelope * cos(phase)
                im[i] = envelope * sin(phase)
            }
        }
        enforceHardWalls()
        normalize()
    }

    /**
     * Single wavepacket carrying a quantized phase singularity of
     * topological charge `charge`:
     *
     *   psi(r, theta) = f(r) * exp(i * charge * theta),
     *   f(r) = (r / sigma)^|charge| * exp(-r^2 / (2 sigma^2))
     *
     * The (r/sigma)^|charge| prefactor is what makes this mathematically
     * sound: a bare phase winding e^{i*l*theta} multiplied by a
     * non-vanishing envelope is discontinuous/multivalued at r = 0.
     * Forcing the amplitude to vanish as r^|l| at the core removes the
     * singularity from |psi| itself -- psi stays smooth and single-valued
     * everywhere on the grid -- while the phase still winds by 2*pi*l
     * around the core, giving a genuine quantized vortex.
     */
    fun setQuantumVortex(
        charge: Int = 1,
        sigmaFrac: Float = 0.20f
    ) {
        clear()
        val cx = width / 2f
        val cy = height / 2f
        val sigma = width * sigmaFrac
        val l = charge

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dxp = x - cx
                val dyp = y - cy
                val r = sqrt(dxp * dxp + dyp * dyp)
                val theta = atan2(dyp, dxp)

                val rn = if (l == 0) 1f else (r / sigma).pow(abs(l))
                val envelope = rn * exp(-(r * r) / (2f * sigma * sigma))
                val phase = l * theta

                val i = index(x, y)
                re[i] = envelope * cos(phase)
                im[i] = envelope * sin(phase)
            }
        }
        enforceHardWalls()
        normalize()
    }
}