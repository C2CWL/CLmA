package com.clma.app.data.vector

/**
 * Which installed GGUF to embed with.
 *
 * Pure, because "the index was built with a different model than the one you are querying with"
 * is the single failure that silently makes every result wrong, and the decision deserves to be
 * a test rather than a line inside a settings screen.
 */
object EmbeddingModelRules {

    /**
     * Picks the model file name to use, or null when there is nothing to embed with.
     *
     * [configured] wins outright when it is actually installed - an explicit choice the user made
     * is never second-guessed, and if the file was deleted the answer is "nothing", not "some
     * other model". Otherwise the first installed file that matches [curatedOrder] is taken, so
     * that installing the recommended model through the ordinary local-model page is enough to
     * make semantic search work without touching another setting.
     */
    fun pick(configured: String?, installed: List<String>, curatedOrder: List<String>): String? {
        val available = installed.toSet()
        val explicit = configured?.trim().orEmpty()
        if (explicit.isNotEmpty()) return explicit.takeIf { it in available }
        return curatedOrder.firstOrNull { it in available }
    }

    /**
     * The identity stored on every row of the index. The file name, not the path: the path
     * contains a versioned app directory that changes on update, and a model id that changes with
     * it would invalidate the entire index on every release.
     */
    fun modelIdOf(fileName: String): String = fileName.substringAfterLast('/')

    /** The extension that means LiteRT-LM, and therefore a different engine behind the same door. */
    const val LITERT_EXTENSION = ".litertlm"

    /**
     * Which engine runs the file.
     *
     * By extension rather than by a setting, because the installed file is the fact and a setting
     * could disagree with it: two engines cannot both be the answer for one file name, and the
     * identity written on every index row is that name.
     */
    fun isLitert(fileName: String): Boolean = fileName.endsWith(LITERT_EXTENSION, ignoreCase = true)
}
