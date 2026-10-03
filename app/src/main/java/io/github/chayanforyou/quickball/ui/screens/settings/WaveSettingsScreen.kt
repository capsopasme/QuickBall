package io.github.chayanforyou.quickball.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.core.QuickBallService
import io.github.chayanforyou.quickball.domain.AppDefaults
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.ui.screens.home.components.SettingSwitchRow
import io.github.chayanforyou.quickball.ui.screens.settings.components.ColorPickerDialog
import io.github.chayanforyou.quickball.ui.screens.settings.components.ColorSettingRow
import io.github.chayanforyou.quickball.ui.screens.settings.components.GestureActionBottomSheet
import io.github.chayanforyou.quickball.ui.screens.settings.components.GestureSettingRow
import io.github.chayanforyou.quickball.ui.screens.settings.components.SliderSettingItem
import io.github.chayanforyou.quickball.ui.theme.AppCardDefaults

private enum class WaveGesture { SWIPE_UP, SWIPE_DOWN }

/**
 * Settings for the swipe-only wave edge bar. Every change is saved immediately and pushed to
 * the running service so the bar updates live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaveSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { AppPreference.getInstance(context) }

    var enabled by remember { mutableStateOf(prefs.isWaveEnabled) }
    var onRight by remember { mutableStateOf(prefs.waveOnRight) }
    var position by remember { mutableFloatStateOf(prefs.waveYFraction * 100f) }
    var height by remember { mutableFloatStateOf(prefs.waveHeight) }
    var touchWidth by remember { mutableFloatStateOf(prefs.waveTouchWidth) }
    var thickness by remember { mutableFloatStateOf(prefs.waveThickness) }
    var color by remember { mutableIntStateOf(prefs.waveColor) }
    var swipeUp by remember { mutableStateOf(prefs.waveSwipeUpAction) }
    var swipeDown by remember { mutableStateOf(prefs.waveSwipeDownAction) }
    var showColorDialog by remember { mutableStateOf(false) }
    var activeGesture by remember { mutableStateOf<WaveGesture?>(null) }

    fun saveAndUpdate(save: () -> Unit) {
        save()
        context.updateWaveBar()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.wave_settings_title),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.menu_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.wave_settings_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )

            // Enable + gestures
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = AppCardDefaults.cardColors()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SettingSwitchRow(
                        title = stringResource(R.string.wave_enable_title),
                        checked = enabled,
                        onCheckedChange = { value ->
                            enabled = value
                            saveAndUpdate { prefs.isWaveEnabled = value }
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    GestureSettingRow(
                        title = stringResource(R.string.swipe_up_title),
                        actionName = swipeUp,
                        enabled = enabled,
                        onClick = { activeGesture = WaveGesture.SWIPE_UP }
                    )
                    GestureSettingRow(
                        title = stringResource(R.string.swipe_down_title),
                        actionName = swipeDown,
                        enabled = enabled,
                        onClick = { activeGesture = WaveGesture.SWIPE_DOWN }
                    )
                }
            }

            // Placement & look
            Text(
                text = stringResource(R.string.wave_appearance_header),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = AppCardDefaults.cardColors()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wave_side_title),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = !onRight,
                            onClick = {
                                onRight = false
                                saveAndUpdate { prefs.waveOnRight = false }
                            },
                            label = { Text(stringResource(R.string.wave_side_left)) }
                        )
                        FilterChip(
                            selected = onRight,
                            onClick = {
                                onRight = true
                                saveAndUpdate { prefs.waveOnRight = true }
                            },
                            label = { Text(stringResource(R.string.wave_side_right)) }
                        )
                    }

                    SliderSettingItem(
                        title = stringResource(R.string.wave_position_title),
                        value = position,
                        valueRange = 0f..100f,
                        steps = 19,
                        unit = "%",
                        onValueChange = { value ->
                            position = value
                            saveAndUpdate { prefs.waveYFraction = value / 100f }
                        },
                        onReset = {
                            position = AppDefaults.WAVE_Y_FRACTION * 100f
                            saveAndUpdate { prefs.waveYFraction = AppDefaults.WAVE_Y_FRACTION }
                        }
                    )

                    SliderSettingItem(
                        title = stringResource(R.string.wave_height_title),
                        value = height,
                        valueRange = 60f..240f,
                        steps = 35,
                        onValueChange = { value ->
                            height = value
                            saveAndUpdate { prefs.waveHeight = value }
                        },
                        onReset = {
                            height = AppDefaults.WAVE_HEIGHT
                            saveAndUpdate { prefs.waveHeight = AppDefaults.WAVE_HEIGHT }
                        }
                    )

                    SliderSettingItem(
                        title = stringResource(R.string.wave_touch_width_title),
                        value = touchWidth,
                        valueRange = 16f..40f,
                        steps = 23,
                        onValueChange = { value ->
                            touchWidth = value
                            saveAndUpdate { prefs.waveTouchWidth = value }
                        },
                        onReset = {
                            touchWidth = AppDefaults.WAVE_TOUCH_WIDTH
                            saveAndUpdate { prefs.waveTouchWidth = AppDefaults.WAVE_TOUCH_WIDTH }
                        }
                    )

                    SliderSettingItem(
                        title = stringResource(R.string.wave_thickness_title),
                        value = thickness,
                        valueRange = 1f..8f,
                        steps = 6,
                        onValueChange = { value ->
                            thickness = value
                            saveAndUpdate { prefs.waveThickness = value }
                        },
                        onReset = {
                            thickness = AppDefaults.WAVE_THICKNESS
                            saveAndUpdate { prefs.waveThickness = AppDefaults.WAVE_THICKNESS }
                        }
                    )

                    ColorSettingRow(
                        title = stringResource(R.string.wave_color_title),
                        color = color,
                        onReset = {
                            color = AppDefaults.WAVE_COLOR
                            saveAndUpdate { prefs.waveColor = AppDefaults.WAVE_COLOR }
                        },
                        onClick = { showColorDialog = true }
                    )
                }
            }
        }
    }

    if (showColorDialog) {
        ColorPickerDialog(
            initialColor = color,
            onDismissRequest = { showColorDialog = false },
            onColorSelected = { selected ->
                color = selected
                saveAndUpdate { prefs.waveColor = selected }
                showColorDialog = false
            }
        )
    }

    activeGesture?.let { gesture ->
        val (titleRes, current) = when (gesture) {
            WaveGesture.SWIPE_UP -> R.string.swipe_up_title to swipeUp
            WaveGesture.SWIPE_DOWN -> R.string.swipe_down_title to swipeDown
        }
        GestureActionBottomSheet(
            title = stringResource(titleRes),
            currentActionName = current,
            onActionSelected = { action ->
                when (gesture) {
                    WaveGesture.SWIPE_UP -> {
                        swipeUp = action.name
                        prefs.waveSwipeUpAction = action.name
                    }

                    WaveGesture.SWIPE_DOWN -> {
                        swipeDown = action.name
                        prefs.waveSwipeDownAction = action.name
                    }
                }
            },
            onDismissRequest = { activeGesture = null }
        )
    }
}

private fun Context.updateWaveBar() {
    startService(
        Intent(this, QuickBallService::class.java).apply {
            action = QuickBallService.ACTION_UPDATE_WAVE
        }
    )
}
