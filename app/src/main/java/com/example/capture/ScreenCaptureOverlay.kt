package com.example.capture

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Full-screen translucent overlay shown when the user taps "Scan Screen" in the Vault tab.
 *
 * Behaviour:
 *   - Renders a draggable + resizable selection rectangle over a dimmed preview of the screen.
 *   - Exactly 4 corner handles (top-left, top-right, bottom-left, bottom-right). NO mid-edge
 *     handles — keeps the UX simple as the user requested.
 *   - The whole rectangle AND all 4 handles derive their geometry from a SINGLE state object
 *     ([SelectionState]) so they can NEVER desync. Previous attempts had bugs where the
 *     handles were tracked with separate offset states that drifted from the outline.
 *   - Forces LTR layout direction via [CompositionLocalProvider] + [LayoutDirection.Ltr]:
 *     the rectangle represents RAW SCREEN COORDINATES that must never mirror when the app
 *     is running in Arabic / Persian RTL UI mode. This is critical: if we let Compose's RTL
 *     mirroring touch this overlay, the captured region would map to the wrong physical
 *     pixels and OCR would silently read the wrong part of the screen.
 *   - On "Capture" tap, the host activity receives the screen-pixel rect via [onCapture]
 *     and proceeds with the MediaProjection-based capture flow.
 *
 * Coordinate system:
 *   - All internal math is done in pixels relative to the overlay's own box, which fills
 *     the screen. Because we force LTR and the overlay is full-screen, these pixel coords
 *     are 1:1 with the raw screen pixels MediaProjection will produce. No further transform
 *     is needed when handing the rect to [ScreenCaptureManager.captureRegion].
 */
@Composable
fun ScreenCaptureOverlay(
    onCancel: () -> Unit,
    onCapture: (Rect) -> Unit
) {
    // CRITICAL: force LTR so RTL UI languages (Arabic, Persian) do NOT mirror the overlay.
    // The selection rect represents raw screen coordinates — mirroring would corrupt the
    // region handed to MediaProjection.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            val context = LocalContext.current
            val displayMetrics = remember(context) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm
            }
            val screenWPx = displayMetrics.widthPixels.toFloat()
            val screenHPx = displayMetrics.heightPixels.toFloat()

            // ----------------------------------------------------------------
            // SINGLE source of truth for the selection rectangle.
            // The outline AND all 4 handles read from this one object.
            // No separately-tracked offsets anywhere — that was the desync bug.
            // ----------------------------------------------------------------
            var selection by remember {
                mutableStateOf(
                    SelectionState.defaultFor(screenWPx, screenHPx)
                )
            }

            // Dimmed-out region: we paint the scrim with a hole punched out for the selection.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            // Dragging inside the body of the rect moves the whole rect.
                            change.consume()
                            selection = selection.translatedBy(
                                dx = drag.x,
                                dy = drag.y,
                                screenW = screenWPx,
                                screenH = screenHPx
                            )
                        }
                    }
            ) {
                val sel = selection
                val selPath = Path().apply {
                    addRect(
                        Rect(
                            left = sel.left,
                            top = sel.top,
                            right = sel.right,
                            bottom = sel.bottom
                        )
                    )
                }
                // Punch the selection out of the dim layer.
                val dimPath = Path().apply {
                    addRect(
                        Rect(
                            left = 0f, top = 0f,
                            right = size.width, bottom = size.height
                        )
                    )
                    op(this, selPath, PathOperation.Difference)
                }
                drawPath(dimPath, color = Color(0x33000000))
                // Selection outline
                drawRect(
                    color = Color(0xFF00E5FF),
                    topLeft = Offset(sel.left, sel.top),
                    size = Size(sel.width, sel.height),
                    style = Stroke(width = 3f)
                )
            }

            // Corner handles layer — read from the SAME `selection` state.
            CornerHandlesLayer(
                selection = selection,
                onTopLeftDrag = { dx, dy ->
                    selection = selection.resizedByCorner(
                        corner = Corner.TOP_LEFT, dx = dx, dy = dy,
                        screenW = screenWPx, screenH = screenHPx
                    )
                },
                onTopRightDrag = { dx, dy ->
                    selection = selection.resizedByCorner(
                        corner = Corner.TOP_RIGHT, dx = dx, dy = dy,
                        screenW = screenWPx, screenH = screenHPx
                    )
                },
                onBottomLeftDrag = { dx, dy ->
                    selection = selection.resizedByCorner(
                        corner = Corner.BOTTOM_LEFT, dx = dx, dy = dy,
                        screenW = screenWPx, screenH = screenHPx
                    )
                },
                onBottomRightDrag = { dx, dy ->
                    selection = selection.resizedByCorner(
                        corner = Corner.BOTTOM_RIGHT, dx = dx, dy = dy,
                        screenW = screenWPx, screenH = screenHPx
                    )
                }
            )

            // Top bar: Cancel button + hint text.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xAAFF4D4D))
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.capture_overlay_cancel))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.capture_overlay_cancel))
                }
                Text(
                    text = stringResource(R.string.capture_overlay_instruction),
                    color = Color.White,
                    fontSize = 13.sp
                )
            }

            // Bottom bar: Capture button centered.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        val s = selection
                        onCapture(
                            Rect(
                                left = s.left,
                                top = s.top,
                                right = s.right,
                                bottom = s.bottom
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.capture_overlay_capture),
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.capture_ocr_btn), color = Color.Black, fontSize = 16.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Single-state-object model for the selection rectangle.
// ----------------------------------------------------------------------

/**
 * Immutable snapshot of the selection rectangle's pixel coordinates.
 *
 * Why immutable: every drag-frame produces a fresh instance via [translatedBy] /
 * [resizedByCorner], so Compose's state diffing always sees a new object and the
 * Canvas + handle layer both recompose from the SAME atomic snapshot. This is the
 * fix for the desync bug where separate mutableStateOf<Float> offsets drifted.
 *
 * Invariants enforced by the builder functions:
 *   - left < right and top < bottom (width/height always positive)
 *   - The rect always stays within [0, screenW] x [0, screenH]
 *   - The rect always satisfies the [MIN_SIDE_PX] minimum size
 */
data class SelectionState(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        /** Minimum side length (in pixels) the selection can be shrunk to. */
        const val MIN_SIDE_PX = 80f

        /** Default selection rect: centered 60% width x 40% height of the screen. */
        fun defaultFor(screenW: Float, screenH: Float): SelectionState {
            val w = screenW * 0.6f
            val h = screenH * 0.4f
            val left = (screenW - w) / 2f
            val top = (screenH - h) / 2f
            return SelectionState(
                left = left,
                top = top,
                right = left + w,
                bottom = top + h
            )
        }
    }

    fun translatedBy(dx: Float, dy: Float, screenW: Float, screenH: Float): SelectionState {
        val w = width
        val h = height
        val newLeft = (left + dx).coerceIn(0f, (screenW - w).coerceAtLeast(0f))
        val newTop = (top + dy).coerceIn(0f, (screenH - h).coerceAtLeast(0f))
        return SelectionState(
            left = newLeft,
            top = newTop,
            right = newLeft + w,
            bottom = newTop + h
        )
    }

    fun resizedByCorner(
        corner: Corner,
        dx: Float,
        dy: Float,
        screenW: Float,
        screenH: Float
    ): SelectionState {
        var newLeft = left
        var newTop = top
        var newRight = right
        var newBottom = bottom
        when (corner) {
            Corner.TOP_LEFT -> {
                newLeft = (left + dx).coerceIn(0f, right - MIN_SIDE_PX)
                newTop = (top + dy).coerceIn(0f, bottom - MIN_SIDE_PX)
            }
            Corner.TOP_RIGHT -> {
                newRight = (right + dx).coerceIn(left + MIN_SIDE_PX, screenW)
                newTop = (top + dy).coerceIn(0f, bottom - MIN_SIDE_PX)
            }
            Corner.BOTTOM_LEFT -> {
                newLeft = (left + dx).coerceIn(0f, right - MIN_SIDE_PX)
                newBottom = (bottom + dy).coerceIn(top + MIN_SIDE_PX, screenH)
            }
            Corner.BOTTOM_RIGHT -> {
                newRight = (right + dx).coerceIn(left + MIN_SIDE_PX, screenW)
                newBottom = (bottom + dy).coerceIn(top + MIN_SIDE_PX, screenH)
            }
        }
        return SelectionState(
            left = newLeft,
            top = newTop,
            right = newRight,
            bottom = newBottom
        )
    }
}

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

// ----------------------------------------------------------------------
// Corner handles layer — reads from the same `selection` state.
// ----------------------------------------------------------------------

@Composable
private fun CornerHandlesLayer(
    selection: SelectionState,
    onTopLeftDrag: (Float, Float) -> Unit,
    onTopRightDrag: (Float, Float) -> Unit,
    onBottomLeftDrag: (Float, Float) -> Unit,
    onBottomRightDrag: (Float, Float) -> Unit
) {
    val handleSizePx = with(LocalDensity.current) { 28.dp.toPx() }
    val half = handleSizePx / 2f

    // For each corner, place a small draggable square centered on the corner point.
    // We use Modifier.offset { IntOffset } with raw pixel coordinates; this is inside
    // the LTR CompositionLocal scope, so no RTL mirroring is applied.
    CornerHandle(
        center = Offset(selection.left, selection.top),
        halfSize = half,
        onDrag = onTopLeftDrag
    )
    CornerHandle(
        center = Offset(selection.right, selection.top),
        halfSize = half,
        onDrag = onTopRightDrag
    )
    CornerHandle(
        center = Offset(selection.left, selection.bottom),
        halfSize = half,
        onDrag = onBottomLeftDrag
    )
    CornerHandle(
        center = Offset(selection.right, selection.bottom),
        halfSize = half,
        onDrag = onBottomRightDrag
    )
}

@Composable
private fun CornerHandle(
    center: Offset,
    halfSize: Float,
    onDrag: (Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val sizeDp = with(density) { (halfSize * 2).toDp() }
    val topLeftIntOffset = IntOffset(
        (center.x - halfSize).roundToInt(),
        (center.y - halfSize).roundToInt()
    )
    Box(
        modifier = Modifier
            .offset { topLeftIntOffset }
            .size(sizeDp)
            .background(Color.White, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
    ) {
        // Inner dot so the handle is visible on white backgrounds too.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .background(Color(0xFF00E5FF), RoundedCornerShape(2.dp))
        )
    }
}
