package com.clma.app.data.vector

import android.content.Context
import java.io.File
import com.clma.llamacpp.LlamaCppEmbeddingCatalog
import com.clma.locallm.LocalRuntime
import com.clma.locallm.ModelInstall
import com.clma.locallm.litert.LitertEmbeddingCatalog

/**
 * Where the embedding model files are - one answer, shared by both sides of the same coin.
 *
 * The download path writes through [ModelInstall.targetFile], the SAF import writes through it
 * too, and the embedding lookup reads, so all three have to agree on a directory. They are kept
 * in one place here rather than spelled out at each call site, because they did not agree once:
 * the lookup listed `local-models/` while everything else wrote to `local-models/llamacpp/`, so
 * an installed model was invisible and semantic search stayed silent no matter what the user did.
 *
 * ## Two directories now, not one
 *
 * The embedding backend used to be llama.cpp alone, so one directory was enough. It is now either
 * runtime, and the installed file is what decides which one runs:
 *
 *  * `local-models/litert/`  - `.litertlm`, run by LiteRT-LM ([LitertEmbeddingCatalog])
 *  * `local-models/llamacpp/` - `.gguf`, run by llama.cpp ([LlamaCppEmbeddingCatalog])
 *
 * Both are looked in, and the lookup is by name across both, because [EmbeddingModelRules.pick]
 * reasons about names and the identity stored on every index row is a name. The *order* the
 * directories are searched does not matter as long as the file names are distinct - and they are,
 * since no file can be both a GGUF and a `.litertlm`.
 */
object EmbeddingModelFiles {

    /** `local-models/litert/` - where `.litertlm` embedding models live. */
    fun litertDir(baseDir: File): File = dir(baseDir, LocalRuntime.LiteRT)

    fun litertDir(context: Context): File = litertDir(ModelInstall.localModelsDir(context))

    /** The llama.cpp directory - kept for the download path, which writes GGUFs there. */
    fun dir(baseDir: File): File = dir(baseDir, LocalRuntime.LlamaCpp)

    /** The same directory, for the app's real base directory. */
    fun dir(context: Context): File = dir(ModelInstall.localModelsDir(context))

    fun dir(baseDir: File, runtime: LocalRuntime): File = ModelInstall.runtimeDir(baseDir, runtime)

    /** Every directory an embedding model may be installed in, for a given base (`local-models/`). */
    fun dirs(baseDir: File): List<File> =
        listOf(LocalRuntime.LiteRT, LocalRuntime.LlamaCpp).map { dir(baseDir, it) }

    fun dirs(context: Context): List<File> = dirs(ModelInstall.localModelsDir(context))

    /** The embedding-capable files present in every directory, by name, sorted. */
    fun installedFiles(baseDir: File): List<String> =
        dirs(baseDir).flatMap { installedFilesIn(it) }.sorted()

    fun installedFiles(context: Context): List<String> = installedFiles(ModelInstall.localModelsDir(context))

    /** The installed file [fileName] names, or null when it is not installed anywhere. */
    fun fileFor(baseDir: File, fileName: String): File? =
        dirs(baseDir).asSequence().map { File(it, fileName) }.firstOrNull { it.isFile }

    fun fileFor(context: Context, fileName: String): File? =
        fileFor(ModelInstall.localModelsDir(context), fileName)

    private fun installedFilesIn(dir: File): List<String> =
        dir.listFiles { file -> file.isFile && isEmbeddingFile(file.name) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    /** The two extensions an embedding model can have; anything else in these directories is not one. */
    private fun isEmbeddingFile(name: String): Boolean =
        name.endsWith(".gguf", ignoreCase = true) || name.endsWith(".litertlm", ignoreCase = true)
}
