package com.clma.app.ui.components.richtext

import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownNumericRangeTest {
    private fun org.intellij.markdown.ast.ASTNode.containsStrikethrough(): Boolean =
        type == GFMElementTypes.STRIKETHROUGH || children.any { it.containsStrikethrough() }

    private val flavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    private val parser = MarkdownParser(flavour)

    @Test
    fun numericRangeDoesNotCreateStrikethrough() {
        val source = "3.6万~4.8万 tokens/次"
        val tree = parser.buildMarkdownTreeFromString(protectNumericRangeTildes(source))

        assertTrue(!tree.containsStrikethrough())
    }

    @Test
    fun explicitStrikethroughStillParses() {
        val tree = parser.buildMarkdownTreeFromString(protectNumericRangeTildes("~~deleted text~~"))

        assertTrue(tree.containsStrikethrough())
    }

    @Test
    fun rangeTildeStaysVisibleAndCodeIsNotModified() {
        assertEquals("3.6万&#126;4.8万", protectNumericRangeTildes("3.6万~4.8万"))
        assertEquals("`3.6万~4.8万`", protectNumericRangeTildes("`3.6万~4.8万`"))
    }
}
