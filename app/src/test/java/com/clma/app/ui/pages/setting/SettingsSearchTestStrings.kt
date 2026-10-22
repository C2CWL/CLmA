package com.clma.app.ui.pages.setting

import com.clma.app.R
import java.io.File
import java.lang.reflect.Modifier
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Resolves `R.string` ids to real text for JVM unit tests (no Robolectric in this module):
 * ids are mapped back to names through the generated R class, and names are looked up in the
 * module's strings.xml files, falling back to the default (English) file like Android does.
 */
internal object SettingsSearchTestStrings {
    private val idToName: Map<Int, String> by lazy {
        R.string::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }
    }

    private fun load(path: String): Map<String, String> {
        val file = File(path)
        require(file.exists()) { "missing $path (unit tests run from the module directory)" }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name") to element.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }

    /** strings.xml plus any split-out strings_*.xml in the same folder (e.g. strings_visual_style.xml). */
    private fun loadDir(dir: String): Map<String, String> {
        val extra = File(dir).listFiles { f -> f.name.startsWith("strings_") && f.name.endsWith(".xml") }
            .orEmpty().sortedBy { it.name }
        return extra.fold(load("$dir/strings.xml")) { acc, f -> acc + load(f.path) }
    }

    val en: Map<String, String> by lazy { loadDir("src/main/res/values") }
    val zh: Map<String, String> by lazy { loadDir("src/main/res/values-zh") }

    fun nameOf(id: Int): String = idToName[id] ?: error("unknown string id $id")

    fun idOf(name: String): Int = R.string::class.java.getField(name).getInt(null)

    /** Chinese UI text (with English fallback for untranslated strings). */
    val chinese: (Int) -> String = { id -> nameOf(id).let { zh[it] ?: en[it] ?: error("no text for $it") } }

    /** English UI text. */
    val english: (Int) -> String = { id -> nameOf(id).let { en[it] ?: error("no text for $it") } }
}
