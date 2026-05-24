package io.itsikh.finnencer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.repo.BugButtonPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import javax.inject.Inject

/**
 * Tri-state position model so the composable can tell apart "DataStore
 * hasn't emitted yet" (Loading) from "the user has never dragged the
 * button" (Unset) from "we have a saved spot" (At). Loading suppresses
 * the button for the first ~frame to avoid painting at the default
 * spot then jumping to the persisted one.
 */
sealed interface BugButtonPos {
    object Loading : BugButtonPos
    object Unset : BugButtonPos
    data class At(val x: Float, val y: Float) : BugButtonPos
}

@HiltViewModel
class BugButtonPositionViewModel @Inject constructor(
    private val prefs: BugButtonPreferences,
) : ViewModel() {

    val position: StateFlow<BugButtonPos> = prefs.position
        .map<Pair<Float, Float>?, BugButtonPos> { p ->
            if (p == null) BugButtonPos.Unset else BugButtonPos.At(p.first, p.second)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BugButtonPos.Loading)

    fun save(x: Float, y: Float) {
        viewModelScope.launch { prefs.setPosition(x, y) }
    }
}

/**
 * A floating, draggable bug-report button rendered as an overlay on all screens.
 *
 * Only rendered when [visible] is `true` (typically when admin mode is active and
 * the "Bug Report Button" toggle is on in Settings → Debug).
 *
 * ## Interaction
 * - **Drag** — repositions the button anywhere on screen. The new
 *   location is persisted via [BugButtonPreferences] when the gesture
 *   ends, so the button reappears in the same spot across recompositions
 *   and app restarts.
 * - **Tap** (total drag distance < 20 px) — hides the button for one frame, captures
 *   a screenshot of the current screen via [View.drawToBitmap], then calls
 *   [onScreenshotCaptured] with the resulting [Bitmap].
 *
 * The caller is responsible for storing the bitmap (e.g. in [bugreport.ScreenshotHolder])
 * and navigating to the bug report screen.
 *
 * @param visible Whether the button should be shown. Pass `false` to hide it completely.
 * @param onScreenshotCaptured Called with the captured screen bitmap after a tap.
 */
@Composable
fun FloatingBugButton(
    visible: Boolean,
    onScreenshotCaptured: (Bitmap) -> Unit
) {
    if (!visible) return

    val vm: BugButtonPositionViewModel = hiltViewModel()
    val positionState by vm.position.collectAsState()

    // Default spawn position: 16dp from the left edge, ~80% down the
    // screen. Used only when the user has never dragged the button.
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val defaultOffsetX = with(density) { 16.dp.toPx() }
    val defaultOffsetY = with(density) { (screenHeightDp * 0.80f).dp.toPx() }

    // Until DataStore emits, keep the button off-screen to avoid a
    // visible jump from "default" → "persisted" on app start.
    when (val s = positionState) {
        BugButtonPos.Loading -> return
        else -> {
            val initial = when (s) {
                is BugButtonPos.At -> s.x to s.y
                else -> defaultOffsetX to defaultOffsetY
            }
            DraggableBugButton(
                initialOffsetX = initial.first,
                initialOffsetY = initial.second,
                onDragEnd = vm::save,
                onScreenshotCaptured = onScreenshotCaptured,
            )
        }
    }
}

@Composable
private fun DraggableBugButton(
    initialOffsetX: Float,
    initialOffsetY: Float,
    onDragEnd: (Float, Float) -> Unit,
    onScreenshotCaptured: (Bitmap) -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var capturing by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(capturing) {
        if (capturing) {
            delay(80) // one frame — lets the button disappear before capture
            val bitmap = view.drawToBitmap()
            capturing = false
            onScreenshotCaptured(bitmap)
        }
    }

    if (!capturing) {
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(52.dp)
                .shadow(6.dp, CircleShape)
                .background(MaterialTheme.colorScheme.error, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var isDragging = false
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.positionChange()
                            totalX += abs(delta.x)
                            totalY += abs(delta.y)
                            if (totalX > 10f || totalY > 10f) {
                                isDragging = true
                                change.consume()
                                offsetX += delta.x
                                offsetY += delta.y
                            }
                        } while (event.changes.any { it.pressed })
                        if (isDragging) {
                            // Persist on gesture-end so we hit DataStore
                            // once per drag instead of on every delta.
                            onDragEnd(offsetX, offsetY)
                        } else {
                            capturing = true
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = "Report Bug",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
