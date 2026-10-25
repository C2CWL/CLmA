package com.clma.app.virtualdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process as AndroidProcess
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

private const val TAG = "VirtualDisplayLauncher"
private const val PROCESS_POLL_INTERVAL_MS = 200L
private const val MAX_ERROR_OUTPUT_CHARS = 4_000
private const val DEFAULT_LAUNCH_TIMEOUT_MS = 15_000L

/** A running privileged virtual display service process and its Binder interface. */
internal class VirtualDisplayHandle(
    val service: IVirtualDisplayService,
    private val process: Process,
) {
    fun isAlive(): Boolean =
        service.asBinder().isBinderAlive && process.isAlive

    fun destroy() {
        runCatching { service.destroy() }
        runCatching { process.destroy() }
    }
}

internal object VirtualDisplayLauncher {

    private val clientToken = Binder()
    private val mutex = Mutex()
    private var currentHandle: VirtualDisplayHandle? = null

    /**
     * Get or launch the privileged virtual display service.
     * Thread-safe; restarts automatically if the existing process died.
     */
    fun getOrLaunch(context: Context, timeoutMs: Long = DEFAULT_LAUNCH_TIMEOUT_MS): IVirtualDisplayService = runBlocking {
        mutex.withLock {
            currentHandle?.takeIf { it.isAlive() }?.let {
                return@runBlocking it.service
            }
            // Clean up any stale handle
            currentHandle?.destroy()
            currentHandle = null

            val handle = launchInternal(context.applicationContext, timeoutMs)
            currentHandle = handle
            handle.service
        }
    }

    fun isServiceRunning(): Boolean =
        currentHandle?.isAlive() == true

    fun currentService(): IVirtualDisplayService? =
        currentHandle?.takeIf { it.isAlive() }?.service

    fun stop() {
        runBlocking {
            mutex.withLock {
                currentHandle?.destroy()
                currentHandle = null
            }
        }
    }

    private suspend fun launchInternal(
        context: Context,
        timeoutMs: Long,
    ): VirtualDisplayHandle {
        val packageName = context.packageName
        val token = UUID.randomUUID().toString()
        val received = CompletableDeferred<IVirtualDisplayService>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getStringExtra(VirtualDisplayProtocol.EXTRA_TOKEN) != token) return
                val binder = intent.extras?.getBinder(VirtualDisplayProtocol.EXTRA_BINDER)
                val service = IVirtualDisplayService.Stub.asInterface(binder)
                if (service == null) {
                    received.completeExceptionally(
                        IllegalStateException("VirtualDisplay service returned an invalid binder.")
                    )
                } else {
                    received.complete(service)
                }
            }
        }

        val worker = HandlerThread("VDLauncherWorker").apply { start() }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VirtualDisplayProtocol.readyAction(packageName)),
            VirtualDisplayProtocol.SENDER_PERMISSION,
            Handler(worker.looper),
            ContextCompat.RECEIVER_EXPORTED,
        )

        var process: Process? = null
        val outputCollectors = mutableListOf<ProcessOutputCollector>()
        fun describeOutput() = outputCollectors.joinToString("") { it.describe() }

        try {
            val cmd = buildLaunchCommand(context, token)
            val started = startProcess(cmd)
            process = started

            outputCollectors += ProcessOutputCollector(started.errorStream).also { it.start() }
            outputCollectors += ProcessOutputCollector(started.inputStream).also { it.start() }

            val service = withTimeout(timeoutMs) {
                while (!received.isCompleted) {
                    if (!started.isAlive) {
                        delay(PROCESS_POLL_INTERVAL_MS)
                        if (received.isCompleted) break
                        error(
                            "VirtualDisplay privileged process exited prematurely with code " +
                                "${runCatching { started.exitValue() }.getOrDefault(-1)}." +
                                describeOutput()
                        )
                    }
                    delay(PROCESS_POLL_INTERVAL_MS)
                }
                received.await()
            }

            service.linkClient(clientToken)
            Log.i(TAG, "VirtualDisplay privileged service connected successfully")
            return VirtualDisplayHandle(service, started)
        } catch (t: Throwable) {
            runCatching { process?.destroy() }
            if (t is TimeoutCancellationException) {
                throw IllegalStateException(
                    "Timed out waiting for VirtualDisplay privileged service after $timeoutMs ms." + describeOutput(),
                    t
                )
            }
            throw t
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            worker.quitSafely()
        }
    }

    private fun startProcess(command: List<String>): Process {
        // 1. Try Shizuku first if available and permission granted
        if (isShizukuAvailable()) {
            return try {
                startShizukuProcess(command)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start privileged display via Shizuku: ${t.message}, trying root fallback")
                startRootProcess("su", command)
            }
        }
        // 2. Fall back to root su
        return startRootProcess("su", command)
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun startShizukuProcess(command: List<String>): Process {
        val shizuku = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            ?: error("Shizuku binder is not available")
        return ShizukuProcess(shizuku.newProcess(command.toTypedArray(), null, null))
    }

    private fun startRootProcess(suPath: String, command: List<String>): Process {
        val process = ProcessBuilder(suPath).start()
        process.outputStream.bufferedWriter().use { writer ->
            writer.write("exec " + command.joinToString(" ") { shellQuote(it) })
            writer.newLine()
        }
        return process
    }

    private fun buildClassPath(context: Context): String {
        val paths = mutableListOf<String>()
        paths.add(context.applicationInfo.sourceDir)
        context.applicationInfo.splitSourceDirs?.let { paths.addAll(it) }
        return paths.joinToString(":")
    }

    private fun buildLaunchCommand(context: Context, token: String): List<String> = listOf(
        "/system/bin/app_process",
        "-Djava.class.path=${buildClassPath(context)}",
        "/system/bin",
        "--nice-name=${context.packageName}:privileged_display",
        VirtualDisplayStarter::class.java.name,
        "--package=${context.packageName}",
        "--user=${AndroidProcess.myUid() / VirtualDisplayProtocol.PER_USER_RANGE}",
        "--token=$token",
    )

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

private class ProcessOutputCollector(private val stream: InputStream) : Thread("VDOutputDrain") {
    private val buffer = StringBuilder()

    init {
        isDaemon = true
    }

    override fun run() {
        runCatching {
            stream.bufferedReader().forEachLine { line ->
                synchronized(buffer) {
                    if (buffer.length < MAX_ERROR_OUTPUT_CHARS) buffer.appendLine(line)
                }
            }
        }
    }

    fun describe(): String {
        val text = synchronized(buffer) { buffer.toString().trim() }
        return if (text.isEmpty()) "" else " Process output: ${text.take(MAX_ERROR_OUTPUT_CHARS)}"
    }
}

private class ShizukuProcess(private val remote: IRemoteProcess) : Process() {
    override fun getOutputStream(): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)

    override fun getInputStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)

    override fun getErrorStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

    override fun waitFor(): Int = remote.waitFor()

    override fun exitValue(): Int = remote.exitValue()

    override fun isAlive(): Boolean = runCatching { remote.alive() }.getOrDefault(false)

    override fun destroy() {
        remote.destroy()
    }
}
