package io.github.chayanforyou.quickball.ui.screens.settings.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.domain.models.GestureBinding
import io.github.chayanforyou.quickball.domain.models.MenuAction
import io.github.chayanforyou.quickball.utils.loadInstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class PickableApp(val packageName: String, val name: String, val icon: ImageBitmap)

/**
 * Picks what a gesture is bound to: one of the [MenuAction]s, or (via "Open app…") an installed
 * app. The result is a [GestureBinding] string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureActionBottomSheet(
    title: String,
    currentBinding: String,
    onBindingSelected: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val availableActions = remember {
        MenuAction.entries.filter { it != MenuAction.LAUNCH_APP }
    }
    val currentApp = remember(currentBinding) { GestureBinding.appPackage(currentBinding) }
    var pickingApp by remember { mutableStateOf(false) }
    // Both lists are long; open the sheet fully instead of stopping half way.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun select(binding: String) {
        onBindingSelected(binding)
        onDismissRequest()
    }

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (pickingApp) 8.dp else 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pickingApp) {
                    IconButton(onClick = { pickingApp = false }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.menu_back)
                        )
                    }
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            if (pickingApp) R.string.gesture_pick_app_title
                            else R.string.select_gesture_action_title
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (pickingApp) {
                AppPickerList(
                    currentApp = currentApp,
                    onPicked = { select(GestureBinding.forApp(it)) }
                )
            } else {
                LazyColumn {
                    item {
                        OpenAppRow(
                            selected = currentApp != null,
                            onClick = { pickingApp = true }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    items(availableActions) { action ->
                        ActionRow(
                            action = action,
                            selected = action.name == currentBinding,
                            onClick = { select(action.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenAppRow(selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = stringResource(R.string.gesture_open_app),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionRow(action: MenuAction, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (action.iconRes != 0) {
                Icon(
                    painter = painterResource(id = action.iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = if (action.titleRes != 0) stringResource(id = action.titleRes) else action.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun AppPickerList(currentApp: String?, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    // Loaded once per sheet, off the main thread; icons are rasterised there as well so the
    // list scrolls without drawing adaptive icon drawables every frame.
    val apps by produceState<List<PickableApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            context.loadInstalledApps().map {
                PickableApp(packageName = it.packageName, name = it.appName, icon = it.icon)
            }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        placeholder = { Text(stringResource(R.string.gesture_search_apps)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp)
    )

    val list = apps
    if (list == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val filtered = remember(query, list) {
        val q = query.trim()
        if (q.isEmpty()) list else list.filter { it.name.contains(q, ignoreCase = true) }
    }

    LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
        items(filtered, key = { it.packageName }) { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPicked(app.packageName) }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                RadioButton(
                    selected = app.packageName == currentApp,
                    onClick = { onPicked(app.packageName) }
                )
            }
        }
    }
}
