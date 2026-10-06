package io.github.chayanforyou.quickball.ui.screens.settings.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.domain.models.GestureBinding
import io.github.chayanforyou.quickball.utils.DensityUtils

private class AppLabel(val name: String, val icon: ImageBitmap?)

@Composable
fun GestureSettingRow(
    title: String,
    actionName: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val menuAction = remember(actionName) { GestureBinding.action(actionName) }
    val appPackage = remember(actionName) { GestureBinding.appPackage(actionName) }
    val appLabel = remember(appPackage) {
        appPackage?.let { pkg ->
            runCatching {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(pkg, 0)
                val px = DensityUtils.dp2px(20f)
                AppLabel(
                    name = pm.getApplicationLabel(info).toString(),
                    icon = pm.getApplicationIcon(info).toBitmap(px, px).asImageBitmap()
                )
            }.getOrNull()
        }
    }

    val actionTitle = when {
        appPackage != null -> appLabel?.name
            ?: stringResource(R.string.gesture_app_missing, appPackage)
        menuAction != null && menuAction.titleRes != 0 -> stringResource(menuAction.titleRes)
        else -> actionName
    }

    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val actionColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = titleColor
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val appIcon = appLabel?.icon
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    alpha = if (enabled) 1f else 0.4f,
                    modifier = Modifier.size(20.dp)
                )
            } else if (menuAction != null && menuAction.iconRes != 0) {
                Icon(
                    painter = painterResource(id = menuAction.iconRes),
                    contentDescription = null,
                    tint = actionColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = actionTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = actionColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
        }
    }
}
