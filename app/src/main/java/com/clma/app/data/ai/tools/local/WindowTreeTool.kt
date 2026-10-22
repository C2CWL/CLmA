package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService

private const val DEFAULT_MAX_NODES = 300
private const val MAX_NODES_HARD_CEILING = 2000

/** How many vanished-node identities a `diff` read reports before truncating. */
private const val MAX_REMOVED_SIGNATURES = 50

/**
 * JSON for one node. [rect] must already hold the node's screen bounds — the caller fetches them
 * once per node so the tree walk and the tree hash share a single `getBoundsInScreen` IPC (that
 * call is a cross-process hop, and it used to run twice per node).
 *
 * Boolean flags are emitted only when noteworthy — `clickable` / `scrollable` / `editable` when
 * true, `enabled` when false — which roughly halves the tokens of the old all-fields form.
 */
internal fun nodeToJson(
    node: AccessibilityNodeInfo,
    windowId: Int,
    traversalIndex: Int,
    rect: Rect,
): JsonObject = buildJsonObject {
    put("node_id", "${windowId}:${traversalIndex}")
    put("bounds", buildJsonArray {
        add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
    })
    node.className?.toString()?.takeIf { it.isNotEmpty() }?.let { put("class", it) }
    node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { put("text", it) }
    node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let {
        put("content_description", it)
    }
    node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { put("view_id", it) }
    if (node.isClickable) put("clickable", true)
    if (node.isScrollable) put("scrollable", true)
    if (node.isEditable) put("editable", true)
    if (!node.isEnabled) put("enabled", false)
}

/**
 * Stable identity for a node across two reads: the same structural parts the screen-state hash
 * uses (class / text / content description / screen bounds). Two nodes with the same signature
 * are indistinguishable to the delta, which is exactly the granularity we want. [rect] must
 * already hold the node's screen bounds.
 */
internal fun nodeSignature(node: AccessibilityNodeInfo, rect: Rect): String =
    "${node.className}|${node.text}|${node.contentDescription}|" +
        "${rect.left},${rect.top},${rect.right},${rect.bottom}"

internal fun defaultFilter(n: AccessibilityNodeInfo, depth: Int): Boolean {
    if (!n.isVisibleToUser) return false
    if (n.isClickable || n.isScrollable || n.isEditable) return true
    val text = n.text?.toString().orEmpty()
    val cd = n.contentDescription?.toString().orEmpty()
    return text.isNotEmpty() || cd.isNotEmpty()
}

/**
 * Finds the root [AccessibilityNodeInfo] of the topmost application window on the given
 * [displayId]. Uses [AccessibilityService.getWindows] (requires
 * `flagRetrieveInteractiveWindows`) and filters by [AccessibilityWindowInfo.getDisplayId]
 * (API 33+). Returns null when no window is found on that display.
 */
internal fun findRootOnDisplay(
    svc: RikkaAccessibilityService,
    displayId: Int,
): AccessibilityNodeInfo? {
    val windows = try {
        svc.windows
    } catch (t: Throwable) {
        null
    } ?: return null
    // Pick the topmost application-type window on the target display.
    // Application windows have type 1 (TYPE_APPLICATION); system windows (status bar,
    // IME) are skipped so the model sees the app content.
    return windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .firstOrNull { it.getDisplayIdCompat() == displayId }
        ?.root
}

/** API-safe wrapper for AccessibilityWindowInfo.getDisplayId (added in API 33). */
private fun android.view.accessibility.AccessibilityWindowInfo.getDisplayIdCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getDisplayId() else 0

fun readWindowTreeTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "read_window_tree",
    description = "Snapshot of the active window's a11y node tree. Default filters to visible nodes that are clickable / scrollable / editable / have text or content_description. verbose=true skips the filter (use sparingly). max_nodes caps result (default 300, max 2000). package_name optionally restricts + errors if the foreground app doesn't match. display_id captures the window tree of a virtual display (requires API 33+; pass the display_id returned by virtual_display_create). Every node carries a node_id you can pass directly to click_node / set_text (preferred over by/value or coordinates). The result includes screen_state identifying the current surface (package, shade_open, ime_visible, display size) and tree_hash. Re-reading a screen that did not move answers {unchanged:true} instead of the whole tree again — pass full=true if you really need the nodes. diff=true returns only the nodes that appeared / disappeared since your previous read: much cheaper after an action.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("verbose", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, return all nodes (default false applies the filter)")
                })
                put("max_nodes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on returned nodes (default 300, max 2000)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "If set, return wrong_foreground_app error if the foreground app does not match")
                })
                put("full", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Always return the whole tree, even when it is unchanged since your last read (default false).")
                })
                put("diff", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Return only the nodes that appeared / disappeared since your previous read on this conversation (default false). Falls back to the full tree when there is no previous read.")
                })
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id to read (default 0 = main display). Pass a virtual display id from virtual_display_create to read its window tree (requires API 33+).")
                })
            }
        )
    },
    execute = { input ->
        me.rerere.rikkahub.service.RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val verbose = input.jsonObject["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxNodesRaw = input.jsonObject["max_nodes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_NODES
        val maxNodes = maxNodesRaw.coerceIn(1, MAX_NODES_HARD_CEILING)
        val pkgFilter = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        val full = input.jsonObject["full"]?.jsonPrimitive?.booleanOrNull ?: false
        val diff = input.jsonObject["diff"]?.jsonPrimitive?.booleanOrNull ?: false
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull ?: 0

        val payload = AccessibilityServiceHandle.withService { svc ->
            // For non-default displays, find windows on that display via getWindows().
            // rootInActiveWindow only returns the default display's active window.
            val root = if (displayId != 0) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    return@withService buildJsonObject {
                        put("error", "display_filter_unsupported")
                        put("reason", "Reading window trees from non-default displays requires API 33+ (Android 13).")
                        put("nodes", buildJsonArray { })
                    }
                }
                findRootOnDisplay(svc, displayId) ?: run {
                    svc.appendLog(
                        ActionLogEntry(
                            type = "read_window_tree",
                            paramsSummary = "no_window_on_display:$displayId",
                            success = false,
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                    return@withService buildJsonObject {
                        put("error", "no_window_on_display")
                        put("display_id", displayId)
                        put("detail", "No accessibility window found on this display. The target app may not have drawn yet, or the accessibility API may not expose windows from app-created virtual displays on this device. Try take_screenshot(display_id=$displayId) for visual inspection instead.")
                        put("nodes", buildJsonArray { })
                    }
                }
            } else {
                svc.rootInActiveWindow
            }
            if (root == null) {
                svc.appendLog(
                    ActionLogEntry(
                        type = "read_window_tree",
                        paramsSummary = "no_active_window",
                        success = false,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                return@withService buildJsonObject {
                    put("error", "no_active_window")
                    put("nodes", buildJsonArray { })
                }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                svc.appendLog(
                    ActionLogEntry(
                        type = "read_window_tree",
                        paramsSummary = "wrong_pkg current=$pkg",
                        success = false,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                    put("nodes", buildJsonArray { })
                }
            }
            val nodes = mutableListOf<JsonObject>()
            val signatures = mutableListOf<String>()
            val rect = Rect()
            val (emitted, seen, truncated) = svc.traverseTree(
                root = root,
                filter = if (verbose) ({ _, _ -> true }) else (::defaultFilter),
                cap = maxNodes,
                emit = { n, _, idx ->
                    n.getBoundsInScreen(rect)
                    nodes.add(nodeToJson(n, root.windowId, idx, rect))
                    signatures.add(nodeSignature(n, rect))
                }
            )
            svc.appendLog(
                ActionLogEntry(
                    type = "read_window_tree",
                    paramsSummary = "$emitted/$seen nodes, pkg=$pkg" + if (verbose) ", verbose" else "",
                    success = true,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            val treeHash = fnv1a64(signatures)
            val conversationKey = invocationContext.callerConversationId
            val previous = TreeSnapshotCache.get(conversationKey)
            TreeSnapshotCache.put(conversationKey, treeHash, signatures)

            val header = buildJsonObject {
                put("package", pkg)
                put("window_title", root.window?.title?.toString().orEmpty())
                put("tree_hash", treeHash)
                put("truncated", truncated)
                put("total_seen", seen)
            }

            val tree = when {
                // Nothing moved since this conversation's last read: the model already holds
                // these nodes, so answer in one line instead of paying for the whole tree again.
                !full && !diff && previous != null && previous.hash == treeHash ->
                    JsonObject(
                        header + buildJsonObject {
                            put("unchanged", true)
                            put("nodes", buildJsonArray { })
                            put(
                                "note",
                                "The tree is identical to your previous read_window_tree — reuse the " +
                                    "nodes you already have. Pass full=true if you really need them again.",
                            )
                            put("screen_state", screenStateJson(svc, screenChanged = false, root = root))
                        }
                    )

                // Explicit delta: only what appeared / disappeared since that read.
                diff && previous != null -> run {
                    val previousSignatures = previous.signatures.toSet()
                    val currentSignatures = signatures.toSet()
                    val added = TreeDelta.addedIndices(signatures, previousSignatures)
                    JsonObject(
                        header + buildJsonObject {
                            put("nodes", buildJsonArray {
                                added.forEach { index -> add(nodes[index]) }
                            })
                            put("removed", buildJsonArray {
                                TreeDelta.removedSignatures(currentSignatures, previous.signatures)
                                    .take(MAX_REMOVED_SIGNATURES)
                                    .forEach { add(it) }
                            })
                            put(
                                "note",
                                "`nodes` lists only what appeared since your previous read; `removed` " +
                                    "lists identities that disappeared. node_ids are current — click them " +
                                    "directly. Pass full=true for the whole tree.",
                            )
                            put("screen_state", screenStateJson(svc, screenChanged = null, root = root))
                        }
                    )
                }

                else -> JsonObject(
                    header + buildJsonObject {
                        put("nodes", buildJsonArray { nodes.forEach { add(it) } })
                        put("screen_state", screenStateJson(svc, screenChanged = null, root = root))
                    }
                )
            }
            // Screen-automation experience memory: inline this app's stored playbook the first
            // time it is read this turn, so the agent reuses what worked last time instead of
            // re-deriving it. Surfaced once per app per turn (AgentTurnTracker) and a no-op when
            // cold memory is not configured.
            val playbook = surfaceAppPlaybook(pkg, invocationContext)
            if (playbook is AppPlaybookSurface.Stored) {
                JsonObject(tree + buildJsonObject { putAppPlaybook(playbook) })
            } else {
                tree
            }
        }
        streamer.streamIfHeadless(invocationContext, "ReadWindowTree")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
