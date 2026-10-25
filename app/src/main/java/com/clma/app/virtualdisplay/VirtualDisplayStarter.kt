package com.clma.app.virtualdisplay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/**
 * Entry point of the privileged virtual display process, run through `app_process` as shell (Shizuku)
 * or root with CLmA's APK on the class path.
 *
 * This process runs under shell/root UID and communicates with the host app process via Binder.
 */
@Keep
object VirtualDisplayStarter {
    private const val TAG = "VirtualDisplayStarter"

    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            start(args)
        } catch (throwable: Throwable) {
            val cause = (throwable as? InvocationTargetException)?.targetException ?: throwable
            Log.e(TAG, "Virtual display privileged service failed to start", cause)
            System.err.println("Virtual display privileged service failed to start: $cause")
            cause.printStackTrace()
            exitProcess(1)
        }
        exitProcess(0)
    }

    private fun start(args: Array<String>) {
        val options = args.mapNotNull { argument ->
            argument.removePrefix("--").split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val packageName = options["package"]?.takeIf { it.isNotBlank() } ?: error("Missing --package.")
        val userId = options["user"]?.toIntOrNull() ?: error("Missing --user.")
        val token = options["token"]?.takeIf { it.isNotBlank() } ?: error("Missing --token.")
        val user = UserHandle.getUserHandleForUid(userId * VirtualDisplayProtocol.PER_USER_RANGE)

        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        val systemContext = createSystemContext()
        val appContext = createPackageContextAsUser(systemContext, packageName, user)
        val service = VirtualDisplayPrivilegedService(appContext)

        val intent = Intent(VirtualDisplayProtocol.readyAction(packageName))
            .setPackage(packageName)
            .putExtras(
                Bundle().apply {
                    putString(VirtualDisplayProtocol.EXTRA_TOKEN, token)
                    putBinder(VirtualDisplayProtocol.EXTRA_BINDER, service)
                }
            )
        systemContext.sendBroadcastAsUser(intent, user)
        Log.i(TAG, "Virtual display privileged service started for $packageName in user $userId")
        Looper.loop()
    }

    @SuppressLint("PrivateApi")
    private fun createSystemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        return activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun createPackageContextAsUser(base: Context, packageName: String, user: UserHandle): Context =
        Context::class.java
            .getMethod(
                "createPackageContextAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                UserHandle::class.java,
            )
            .invoke(base, packageName, Context.CONTEXT_IGNORE_SECURITY, user) as Context
}
