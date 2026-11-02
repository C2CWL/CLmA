package com.clma.app.ui.components.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the bubble contract without starting Compose or touching stored preferences. */
class PopupMenuContractTest {

    @Test
    fun `shared menu keeps one lightweight bubble surface`() {
        val source = menuSource()
        assertTrue(source.contains("val Shape = RoundedCornerShape(16.dp)"))
        assertTrue(source.contains("val ItemShape = RoundedCornerShape(10.dp)"))
        assertTrue(source.contains("surfaceContainerLowest"))
        assertTrue(source.contains("outlineVariant.copy(alpha = 0.55f)"))
        assertTrue(source.contains("shadowElevation = AppMenuDefaults.ShadowElevation"))
        assertFalse(source.contains("DropdownMenuGroup("))
        assertFalse(source.contains("selected = destructive"))
        assertFalse(source.contains("destructive = selected"))
    }

    @Test
    fun `expressive popup only receives popup parameters and styles an inner surface`() {
        val popup = blockAfter(menuSource(), "PopupMenuStyle.EXPRESSIVE -> DropdownMenuPopup(")
        val surface = popup.substringBefore("PopupMenuStyle.CLASSIC")
        assertFalse(Regex("""DropdownMenuPopup\([\s\S]*?\bshape\s*=""").containsMatchIn(popup.substringBefore("Surface(")))
        assertTrue(surface.contains("Surface("))
        assertTrue(surface.contains("shape = AppMenuDefaults.Shape"))
        assertTrue(surface.contains("verticalScroll(rememberScrollState())"))
    }

    @Test
    fun `menu item maps state through supported material parameters`() {
        val item = blockAfter(menuSource(), "fun AppDropdownMenuItem(")
        assertTrue(item.contains("destructive -> colors.error"))
        assertTrue(item.contains("selected == true -> colors.primary"))
        assertTrue(item.contains("colors = MenuDefaults.itemColors("))
        assertTrue(item.contains("HugeIcons.Tick02"))
        assertTrue(item.contains("this.selected = selected"))
        assertFalse(Regex("""\bDropdownMenuItem\([\s\S]*\bdestructive\s*=""").containsMatchIn(item))
    }

    @Test
    fun `delete rows use the destructive contract`() {
        val required = listOf(
            "ui/pages/chat/ConversationList.kt",
            "ui/pages/chat/ChatDrawer.kt",
            "ui/pages/extensions/skills/SkillsPage.kt",
            "ui/pages/extensions/workspace/WorkspacePage.kt",
        )
        required.forEach { relative ->
            val source = File("src/main/java/com/clma/app/$relative").readText()
            assertTrue(relative, source.contains("destructive = true"))
        }
    }

    @Test
    fun `new chat menus stay compact and use consistent action icons in both top bars`() {
        listOf("ChatPage.kt", "CleanTopBar.kt").forEach { file ->
            val source = File("src/main/java/com/clma/app/ui/pages/chat/$file").readText()
            val menu = blockAfter(source, "AppDropdownMenu(")
            assertTrue(file, menu.contains("modifier = Modifier.widthIn(max = 240.dp)"))
            assertTrue(file, menu.contains("icon = HugeIcons.PlusSign"))
            assertTrue(file, menu.contains("icon = HugeIcons.Shield01"))
            assertTrue(file, menu.contains("supporting = stringResource(R.string.chat_page_temporary_chat_desc)"))
            assertTrue(file, menu.contains("onNewChat()"))
            assertTrue(file, menu.contains("onNewTemporaryChat()"))
            assertFalse(file, source.contains("HugeIcons.MessageAdd01"))
            assertFalse(file, source.contains("HugeIcons.ViewOff"))
        }
    }

    private fun menuSource(): String =
        File("src/main/java/com/clma/app/ui/components/ui/ExpressiveMenu.kt").readText()

    private fun blockAfter(source: String, marker: String): String =
        source.substring(source.indexOf(marker))
}
