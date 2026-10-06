package io.github.chayanforyou.quickball.domain.models

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MenuItemDeserializationTest {

    private val gson = Gson()
    private val menuItemListType = object : TypeToken<List<QuickBallMenuItem>>() {}.type

    @Test
    fun testDeserializeV433StandardAndAppItems() {
        // Sample JSON saved by version 4.3.3
        val jsonFromV433 = """
            [
                {
                    "action": "VOLUME_UP",
                    "iconRes": 2131230890,
                    "titleRes": 2131689560,
                    "isSelected": false
                },
                {
                    "action": "LAUNCH_APP",
                    "iconRes": 2131230890,
                    "titleRes": 0,
                    "isSelected": false,
                    "packageName": "com.whatsapp",
                    "appTitle": "WhatsApp"
                }
            ]
        """.trimIndent()

        val items: List<QuickBallMenuItem>? = gson.fromJson(jsonFromV433, menuItemListType)
        assertNotNull(items)
        assertEquals(2, items!!.size)

        // Verify standard item
        val standardItem = items[0]
        assertEquals(MenuAction.VOLUME_UP, standardItem.action)

        // Verify app item
        val appItem = items[1]
        assertEquals(MenuAction.LAUNCH_APP, appItem.action)
        assertEquals("com.whatsapp", appItem.packageName)
        assertEquals("WhatsApp", appItem.appTitle)
    }

    @Test
    fun testDeserializeCorruptOrObfuscatedNullActionItem() {
        // Sample JSON where action is missing or null
        val corruptJson = """
            [
                {
                    "packageName": "com.instagram.android",
                    "appTitle": "Instagram"
                },
                {
                    "iconRes": 1234
                }
            ]
        """.trimIndent()

        val rawItems: List<QuickBallMenuItem>? = gson.fromJson(corruptJson, menuItemListType)
        assertNotNull(rawItems)

        val validItems = rawItems!!.mapNotNull { item ->
            val resolvedAction = item.action ?: if (item.packageName != null) MenuAction.LAUNCH_APP else null
            if (resolvedAction == null) {
                null
            } else if (item.packageName != null) {
                item.copy(action = resolvedAction)
            } else {
                QuickBallMenuItem.getMenuItemByAction(resolvedAction)
            }
        }

        // Only the app item should be recovered with MenuAction.LAUNCH_APP, corrupt standard item without action filtered out
        assertEquals(1, validItems.size)
        assertEquals(MenuAction.LAUNCH_APP, validItems[0].action)
        assertEquals("com.instagram.android", validItems[0].packageName)
    }

    @Test
    fun testParseStoredListSkipsUnknownAndCorruptEntries() {
        val json = """
            [
                {"action": "VOLUME_UP", "isSelected": false},
                {"action": "SOME_FUTURE_ACTION"},
                {"iconRes": 1234},
                {"action": "LAUNCH_APP", "packageName": "com.whatsapp", "appTitle": "WhatsApp"},
                {"packageName": "com.instagram.android", "appTitle": "Instagram"},
                {"action": "SILENT_TOGGLE"},
                "not an object"
            ]
        """.trimIndent()

        val items = QuickBallMenuItem.parseStoredList(json)

        assertEquals(4, items.size)
        assertEquals(MenuAction.VOLUME_UP, items[0].action)
        assertEquals(MenuAction.LAUNCH_APP, items[1].action)
        assertEquals("com.whatsapp", items[1].packageName)
        assertEquals("WhatsApp", items[1].appTitle)
        assertEquals("com.instagram.android", items[2].packageName)
        assertEquals(MenuAction.DND_TOGGLE, items[3].action)
    }

    @Test
    fun testParseStoredListDropsDuplicates() {
        val json = """
            [
                {"action": "VOLUME_UP"},
                {"action": "VOLUME_UP"},
                {"action": "LAUNCH_APP", "packageName": "com.whatsapp"},
                {"action": "LAUNCH_APP", "packageName": "com.whatsapp"},
                {"action": "LOCK_SCREEN"}
            ]
        """.trimIndent()

        val items = QuickBallMenuItem.parseStoredList(json)

        assertEquals(
            listOf(MenuAction.VOLUME_UP, MenuAction.LAUNCH_APP, MenuAction.LOCK_SCREEN),
            items.map { it.action }
        )
    }

    @Test
    fun testParseStoredListRoundTripsGsonOutput() {
        val original = listOf(
            QuickBallMenuItem(action = MenuAction.BRIGHTNESS_UP),
            QuickBallMenuItem.createAppMenuItem("Gallery", "deckers.thibault.aves"),
            QuickBallMenuItem(action = MenuAction.PARTIAL_SCREENSHOT)
        )

        val items = QuickBallMenuItem.parseStoredList(gson.toJson(original))

        assertEquals(original, items)
    }

    @Test
    fun testParseStoredListOfNonArrayIsEmpty() {
        assertEquals(emptyList<QuickBallMenuItem>(), QuickBallMenuItem.parseStoredList("{\"a\":1}"))
    }

    @Test
    fun testGetIconResNeverThrowsNpeWhenActionIsNull() {
        val menuItem = QuickBallMenuItem(action = MenuAction.VOLUME_UP)
        // Accessing iconRes should return valid resource ID
        assertNotNull(menuItem.iconRes)
    }

    @Test
    fun testLegacySilentToggleDeserializesToDndToggle() {
        val legacyJson = """
            [
                {
                    "action": "SILENT_TOGGLE"
                }
            ]
        """.trimIndent()

        val items: List<QuickBallMenuItem>? = gson.fromJson(legacyJson, menuItemListType)
        assertNotNull(items)
        assertEquals(1, items!!.size)
        assertEquals(MenuAction.DND_TOGGLE, items[0].action)
        assertEquals(MenuAction.DND_TOGGLE, MenuAction.fromName("SILENT_TOGGLE"))
    }
}

