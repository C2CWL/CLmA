package com.clma.app.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart

private const val TAG_LOC = "LocationTool"

private fun errorPayload(message: String, recovery: String? = null): JsonObject =
    buildJsonObject {
        put("error", message)
        if (recovery != null) put("recovery", recovery)
    }

private fun JsonObjectBuilder.putLocation(loc: Location, providerName: String) {
    put("latitude", loc.latitude)
    put("longitude", loc.longitude)
    put("accuracy_m", loc.accuracy)
    if (loc.hasAltitude()) put("altitude", loc.altitude)
    if (loc.hasSpeed()) put("speed", loc.speed)
    if (loc.hasBearing()) put("bearing", loc.bearing)
    put("provider", providerName)
    put("timestamp_ms", loc.time)
}

fun locationTool(context: Context): Tool = Tool(
    name = "get_location",
    description = """
        Get the device's current location (latitude, longitude, accuracy).
        Requires fine location permission and location services to be enabled.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accuracy", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Location accuracy preference: high, balanced (default), or low"
                    )
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Timeout in milliseconds (default 30000, min 1000, max 60000). After timeout, the tool falls back to the most recent cached fix instead of failing."
                    )
                })
            }
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val accuracyStr = params["accuracy"]?.jsonPrimitive?.contentOrNull ?: "balanced"
        if (accuracyStr !in setOf("high", "balanced", "low")) {
            return@Tool listOf(
                UIMessagePart.Text(errorPayload("unknown accuracy: $accuracyStr").toString())
            )
        }

        val payload: JsonObject = when {
            !PermissionHelper.hasRuntime(
                context,
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            ) -> errorPayload("permission ACCESS_FINE_LOCATION not granted")

            else -> {
                val lm = context.getSystemService(LocationManager::class.java)
                if (lm == null) {
                    errorPayload("location services disabled")
                } else {
                    val gpsEnabled = try {
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    } catch (_: Throwable) {
                        false
                    }
                    val networkEnabled = try {
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!gpsEnabled && !networkEnabled) {
                        errorPayload(
                            "location services disabled",
                            "Ask the user to enable Location in Settings → Location."
                        )
                    } else {
                        val cachedNow: Location? =
                            try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
                                ?: try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: SecurityException) { null }
                                ?: try { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (_: SecurityException) { null }
                        val cachedAgeMs = cachedNow?.let { System.currentTimeMillis() - it.time }
                        if (cachedNow != null) {
                            Log.i(TAG_LOC, "returning cached fix age=${cachedAgeMs}ms provider=${cachedNow.provider}")
                            buildJsonObject {
                                putLocation(cachedNow, cachedNow.provider ?: "lm")
                                put("cached", true)
                                if (cachedAgeMs != null) put("age_ms", cachedAgeMs)
                            }
                        } else {
                            Log.w(TAG_LOC, "no cached fix: gps=$gpsEnabled net=$networkEnabled")
                            errorPayload(
                                "no fix available",
                                "No cached location. Ask the user to open a maps app once, or enable Wi-Fi so the system can seed a network fix."
                            )
                        }
                    }
                }
            }
        }
                listOf(UIMessagePart.Text(payload.toString()))
    }
)
