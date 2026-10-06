package io.github.chayanforyou.quickball.domain.models

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

@Keep
data class QuickBallMenuItem(
    @SerializedName("action")
    val action: MenuAction = MenuAction.LAUNCH_APP,
    @SerializedName("isSelected")
    val isSelected: Boolean = false,
    @SerializedName("packageName")
    val packageName: String? = null,
    @SerializedName("appTitle")
    val appTitle: String? = null
) {
    val iconRes: Int
        @DrawableRes get() = action.iconRes

    val titleRes: Int
        @StringRes get() = action.titleRes

    companion object {
        fun getAllMenuItems(): List<QuickBallMenuItem> {
            return MenuAction.entries
                .filter { it != MenuAction.LAUNCH_APP }
                .map { QuickBallMenuItem(action = it) }
        }

        fun getMenuItemByAction(action: MenuAction): QuickBallMenuItem? {
            if (action == MenuAction.LAUNCH_APP) return null
            return QuickBallMenuItem(action = action)
        }

        fun createAppMenuItem(
            appName: String,
            packageName: String
        ): QuickBallMenuItem {
            return QuickBallMenuItem(
                action = MenuAction.LAUNCH_APP,
                packageName = packageName,
                appTitle = appName
            )
        }

        /**
         * Reads the stored menu (a JSON array written by Gson) field by field.
         *
         * Gson's reflective parse writes null into the non-null [action] for an action name this
         * version doesn't know (e.g. after a downgrade); using such an item threw, and the
         * catch-all then wiped the user's whole menu. Here an unknown entry is simply skipped,
         * and duplicates are dropped because the menu editor's list needs unique keys.
         *
         * @throws com.google.gson.JsonParseException if [json] is not valid JSON.
         */
        fun parseStoredList(json: String): List<QuickBallMenuItem> {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) return emptyList()
            return root.asJsonArray
                .mapNotNull { element ->
                    val obj = if (element.isJsonObject) element.asJsonObject else return@mapNotNull null
                    val packageName = obj.stringOrNull("packageName")?.takeIf { it.isNotBlank() }
                    if (packageName != null) {
                        QuickBallMenuItem(
                            action = MenuAction.LAUNCH_APP,
                            packageName = packageName,
                            appTitle = obj.stringOrNull("appTitle")
                        )
                    } else {
                        MenuAction.fromName(obj.stringOrNull("action"))?.let { getMenuItemByAction(it) }
                    }
                }
                .distinctBy { it.packageName ?: it.action.name }
        }

        private fun JsonObject.stringOrNull(key: String): String? {
            val value = get(key) ?: return null
            return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
        }
    }

    fun getTitle(context: Context): String {
        return appTitle ?: if (titleRes != 0) {
            try {
                context.getString(titleRes)
            } catch (_: Exception) {
                ""
            }
        } else ""
    }
}

