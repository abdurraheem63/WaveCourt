package com.wavecourt.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.nio.ByteBuffer

/**
 * Racket overlay visibility, set by the menu (Phase 3 wires the actual
 * StateFlow / ViewModel binding).
 */
enum class RacketOverlayMode { VISIBLE, INVISIBLE }

/**
 * Renders the physics engine's probability density using an AGSL runtime
 * shader, with an optional racket-glow overlay drawn as a separate Compose
 * layer so it can be toggled independently of the density shader itself.
 *
 * Requires API 33 (Android 13) for [RuntimeShader]. Wave Court's shader
 * approach depends on AGSL, so this composable has a hard floor of API 33;
 * if the project's minSdk is lower, a non-AGSL fallback (e.g. a manual
 * Canvas heatmap, or a precomputed `ImageBitmap` gradient LUT sampled per
 * pixel in software) would need to be added as a separate code path. Flag
 * this to confirm the target minSdk before Phase 3 locks in the rendering
 * approach everywhere else.
 *
 * @param racketPositions racket centers in normalized `[0,1]` UV space
 *   (top-left origin), independent of screen size. Phase 3 supplies these
 *   from live touch/AI state; empty by default so this composable is
 *   testable standalone.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun WaveCourtRenderer(
    densityExtractor: DensityExtractor,
    modifier: Modifier = Modifier,
    racketPositions: List<Offset> = emptyList(),
    racketOverlayMode: RacketOverlayMode = RacketOverlayMode.VISIBLE
) {
    val frameTick by densityExtractor.frameTick.collectAsStateWithLifecycle()
    val gridWidth = densityExtractor.gridWidth
    val gridHeight = densityExtractor.gridHeight

    // Persistent across recompositions/frames -- no per-frame allocation.
    // ALPHA_8 is a single byte per texel, the cheapest bitmap format
    // available and exactly what an 0..1 scalar density field needs.
    val densityBitmap = remember {
        Bitmap.createBitmap(gridWidth, gridHeight, Bitmap.Config.ALPHA_8)
    }
    val uploadBuffer = remember { ByteArray(gridWidth * gridHeight) }
    val runtimeShader = remember { RuntimeShader(WAVE_DENSITY_SHADER) }
    val brush = remember(runtimeShader, densityBitmap) {
        DensityShaderBrush(runtimeShader, densityBitmap, gridWidth, gridHeight)
    }

    // Re-upload the density bitmap whenever a new frame is published. This
    // is a plain byte memcpy of a small (16-64K texel) buffer -- distinct
    // from the actual density computation, which already happened on
    // Dispatchers.Default inside DensityExtractor.extract(). Copying bytes
    // into a bitmap is standard main-thread texture-upload work, not
    // "heavy" physics/extraction work, so it's fine here.
    LaunchedEffect(frameTick) {
        val frame = densityExtractor.latestFrame
        for (i in frame.indices) {
            val byteVal = (frame[i] * 255f).toInt().coerceIn(0, 255)
            uploadBuffer[i] = byteVal.toByte()
        }
        densityBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(uploadBuffer))
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Explicitly read frameTick inside the draw scope so this
            // Canvas is invalidated on every new frame -- without this,
            // Compose's scoped recomposition could skip redrawing since
            // the draw lambda otherwise reads no changing state directly
            // (the bitmap mutates in place; only frameTick signals "new
            // content is ready").
            @Suppress("UNUSED_EXPRESSION")
            frameTick
            drawRect(brush = brush, size = size)
        }

        if (racketOverlayMode == RacketOverlayMode.VISIBLE) {
            RacketOverlay(
                racketPositions = racketPositions,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Wraps the AGSL [RuntimeShader] as a Compose [ShaderBrush], binding the
 * density bitmap as the shader's input texture and the grid/screen
 * resolutions as uniforms every time the draw size changes.
 */
private class DensityShaderBrush(
    private val runtimeShader: RuntimeShader,
    private val densityBitmap: Bitmap,
    private val gridWidth: Int,
    private val gridHeight: Int
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val bitmapShader = BitmapShader(
            densityBitmap,
            AndroidShader.TileMode.CLAMP,
            AndroidShader.TileMode.CLAMP
        )
        runtimeShader.setInputShader("densityTexture", bitmapShader)
        runtimeShader.setFloatUniform("gridSize", gridWidth.toFloat(), gridHeight.toFloat())
        runtimeShader.setFloatUniform("resolution", size.width, size.height)
        return runtimeShader
    }
}

/**
 * Soft red radial glow at each racket position, faded out from the touch
 * point. Drawn as its own layer on top of the density shader so toggling
 * "Visual" vs "Invisible" racket mode never touches the shader/bitmap
 * pipeline above.
 */
@Composable
private fun RacketOverlay(
    racketPositions: List<Offset>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        for (uv in racketPositions) {
            val center = Offset(uv.x * size.width, uv.y * size.height)
            val radius = size.minDimension * 0.12f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Red.copy(alpha = 0.55f),
                        Color.Red.copy(alpha = 0f)
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}