package ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Canonical storage/display form of the push-to-talk combo, e.g. "Ctrl+Shift+Space". */
const val PTT_DEFAULT_COMBO = "Ctrl+Shift+Space"

/**
 * Legacy placeholder persisted by the old fake keybind surface; treated as
 * "not customized" everywhere a stored value is consumed.
 */
const val PTT_UNASSIGNED = "Unassigned"

/**
 * Keys offered for push-to-talk. Letters, digits, F-keys and Space cover the
 * practical Discord-style binds while keeping the label table hand-checkable;
 * every entry round-trips label -> Key -> label exactly.
 */
private val pttKeyTable: List<Pair<String, Key>> = listOf(
    "A" to Key.A, "B" to Key.B, "C" to Key.C, "D" to Key.D, "E" to Key.E,
    "F" to Key.F, "G" to Key.G, "H" to Key.H, "I" to Key.I, "J" to Key.J,
    "K" to Key.K, "L" to Key.L, "M" to Key.M, "N" to Key.N, "O" to Key.O,
    "P" to Key.P, "Q" to Key.Q, "R" to Key.R, "S" to Key.S, "T" to Key.T,
    "U" to Key.U, "V" to Key.V, "W" to Key.W, "X" to Key.X, "Y" to Key.Y,
    "Z" to Key.Z,
    "1" to Key.One, "2" to Key.Two, "3" to Key.Three, "4" to Key.Four,
    "5" to Key.Five, "6" to Key.Six, "7" to Key.Seven, "8" to Key.Eight,
    "9" to Key.Nine, "0" to Key.Zero,
    "F1" to Key.F1, "F2" to Key.F2, "F3" to Key.F3, "F4" to Key.F4,
    "F5" to Key.F5, "F6" to Key.F6, "F7" to Key.F7, "F8" to Key.F8,
    "F9" to Key.F9, "F10" to Key.F10, "F11" to Key.F11, "F12" to Key.F12,
    "Space" to Key.Spacebar
)

fun pttKeyLabel(key: Key): String? = pttKeyTable.firstOrNull { it.second == key }?.first

data class PttBinding(val ctrl: Boolean, val shift: Boolean, val alt: Boolean, val key: Key)

/**
 * Parses a stored combo into a matchable binding. Requires Ctrl or Alt in the
 * combo so chat typing can never trigger push-to-talk accidentally.
 */
fun parsePttCombo(stored: String?): PttBinding? {
    if (stored.isNullOrBlank()) return null
    var ctrl = false
    var shift = false
    var alt = false
    var keyPart: String? = null
    for (tokenRaw in stored.split('+')) {
        when (tokenRaw.trim().lowercase()) {
            "" -> continue
            "ctrl", "control" -> ctrl = true
            "shift" -> shift = true
            "alt" -> alt = true
            else -> keyPart = tokenRaw.trim()
        }
    }
    if (!ctrl && !alt) return null
    val key = pttKeyTable.firstOrNull { it.first == keyPart }?.second ?: return null
    return PttBinding(ctrl, shift, alt, key)
}

/** Legacy "Unassigned" values fall back to the shipped default combo. */
fun normalizedPttKeybind(stored: String?): String =
    if (stored.isNullOrBlank() || stored == PTT_UNASSIGNED) PTT_DEFAULT_COMBO else stored

/** Storage spacing ("Ctrl+Shift+Space") -> display spacing ("Ctrl + Shift + Space"). */
fun displayPttCombo(stored: String): String = normalizedPttKeybind(stored).replace("+", " + ")

@Composable
internal fun PttKeybindCaptureDialog(
    currentDisplay: String,
    onCaptured: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var hint by remember { mutableStateOf<String?>("Press any combination (must include Ctrl or Alt)") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    if (event.key == Key.Escape) {
                        onDismiss()
                        return@onPreviewKeyEvent true
                    }
                    val label = pttKeyLabel(event.key)
                        ?: run {
                            hint = "That key isn't supported — try a letter, number, F-key or Space"
                            return@onPreviewKeyEvent true
                        }
                    val ctrl = event.isCtrlPressed
                    val shift = event.isShiftPressed
                    val alt = event.isAltPressed
                    if (!ctrl && !alt) {
                        hint = "Add Ctrl or Alt so typing in chat never triggers it"
                        return@onPreviewKeyEvent true
                    }
                    val parts = buildList {
                        if (ctrl) add("Ctrl")
                        if (shift) add("Shift")
                        if (alt) add("Alt")
                        add(label)
                    }
                    onCaptured(parts.joinToString("+"))
                    onDismiss()
                    true
                }
        ) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1A1B1E), RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Set Push to Talk keybind",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Current: $currentDisplay",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        hint ?: "",
                        color = Color(0xFF23A55A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    "Esc to cancel",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 11.sp
                )
            }
        }
    }
}
