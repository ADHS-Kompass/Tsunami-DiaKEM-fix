/*
 * Adaptive Time Preference for Jetpack Compose
 */

package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.dialogs.TimePickerModal
import app.aaps.core.ui.compose.is24HourClock
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull

/**
 * Composable time of day preference for use inside card sections.
 * The value is stored as minutes from midnight (0-1439). Tapping the row opens a time picker.
 *
 * @param title Optional title override. If null, uses intKey.title
 * @param visibilityContext Optional context for evaluating runtime visibility/enabled conditions
 */
@Composable
fun AdaptiveTimePreferenceItem(
    intKey: IntPreferenceKey,
    title: TextRef? = null,
    visibilityContext: VisibilityContext? = null
) {
    val effectiveTitle = title ?: intKey.title

    val visibility = calculatePreferenceVisibility(
        preferenceKey = intKey,
        engineeringModeOnly = intKey.engineeringModeOnly,
        visibilityContext = visibilityContext
    )

    if (!visibility.visible) return

    val state = rememberPreferenceIntState(intKey)
    val minutes = state.value.coerceIn(0, MINUTES_PER_DAY - 1)
    val dateUtil = LocalDateUtil.current
    val timeText = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(minutes * 60))
    val summary = stringResourceOrNull(intKey.summary)
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        TimePickerModal(
            onTimeSelected = { hour, minute -> state.value = hour * 60 + minute },
            onDismiss = { showPicker = false },
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            is24Hour = is24HourClock()
        )
    }

    Preference(
        title = { PreferenceTitleWithSyncBadge(effectiveTitle, intKey) },
        summary = {
            Column {
                Text(timeText)
                if (summary != null) Text(summary)
            }
        },
        enabled = visibility.enabled,
        onClick = if (visibility.enabled) ({ showPicker = true }) else null
    )
}

private const val MINUTES_PER_DAY = 24 * 60
