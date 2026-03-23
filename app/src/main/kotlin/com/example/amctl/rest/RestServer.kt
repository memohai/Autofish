package com.example.amctl.rest

import com.example.amctl.mcp.auth.BearerTokenAuth
import com.example.amctl.services.accessibility.AccessibilityServiceProvider
import com.example.amctl.services.accessibility.AccessibilityTreeParser
import com.example.amctl.services.accessibility.CompactTreeFormatter
import com.example.amctl.services.accessibility.ElementFinder
import com.example.amctl.services.accessibility.FindBy
import com.example.amctl.services.accessibility.MultiWindowResult
import com.example.amctl.services.accessibility.ScrollAmount
import com.example.amctl.services.accessibility.ScrollDirection
import com.example.amctl.services.accessibility.WindowData
import com.example.amctl.services.system.ToolRouter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RestServer(
    private val port: Int,
    private val bindAddress: String,
    private val bearerToken: String,
    private val toolRouter: ToolRouter,
    private val accessibilityProvider: AccessibilityServiceProvider,
    private val treeParser: AccessibilityTreeParser,
    private val compactTreeFormatter: CompactTreeFormatter,
    private val elementFinder: ElementFinder,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val overlayManager = OverlayManager()
    private var overlayScheduler: ScheduledExecutorService? = null
    @Volatile
    private var overlayConfig = OverlayConfig()

    fun start() {
        server = embeddedServer(Netty, port = port, host = bindAddress) {
            install(ContentNegotiation) { json(json) }
            install(BearerTokenAuth) { token = bearerToken }

            routing {
                get("/health") {
                    call.respondText("""{"status":"healthy","type":"rest"}""", ContentType.Application.Json)
                }

                route("/api") {
                    screenRoutes()
                    touchRoutes()
                    keyRoutes()
                    textRoutes()
                    nodeRoutes()
                    overlayRoutes()
                    appRoutes()
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        stopOverlayAutoRefresh()
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        server = null
    }

    @Synchronized
    fun setOverlayVisible(visible: Boolean): Result<OverlayStatePayload> {
        if (!visible) {
            stopOverlayAutoRefresh()
            val result = overlayManager.setEnabled(false)
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: IllegalStateException("Overlay disable failed"))
            }
            overlayConfig = overlayConfig.copy(enabled = false)
            return Result.success(overlayManager.state().toPayload())
        }

        val snapshot = collectScreenSnapshot()
        val marks = buildMarks(
            windows = snapshot.windows,
            interactiveOnly = overlayConfig.interactiveOnly,
            maxMarks = overlayConfig.maxMarks,
        )
        val result = overlayManager.setEnabled(
            true,
            marks,
            offsetX = overlayConfig.offsetX,
            offsetY = overlayConfig.offsetY,
        )
        if (result.isFailure) {
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException("Overlay enable failed"))
        }
        overlayConfig = overlayConfig.copy(enabled = true)
        if (overlayConfig.autoRefresh) {
            startOverlayAutoRefresh()
        } else {
            stopOverlayAutoRefresh()
        }
        return Result.success(overlayManager.state().toPayload())
    }

    @Serializable
    data class ApiResponse(val ok: Boolean, val data: String? = null, val error: String? = null)

    private fun ok(data: String) = json.encodeToString(ApiResponse.serializer(), ApiResponse(ok = true, data = data))
    private fun err(msg: String) = json.encodeToString(ApiResponse.serializer(), ApiResponse(ok = false, error = msg))

    @Suppress("LongMethod")
    private fun io.ktor.server.routing.Route.screenRoutes() {
        get("/screen") {
            val snapshot = collectScreenSnapshot()
            val result = MultiWindowResult(windows = snapshot.windows, degraded = snapshot.degraded)
            val modeHeader = "[mode: ${snapshot.mode}]"
            val tsv = "$modeHeader\n${compactTreeFormatter.formatMultiWindow(result, snapshot.screenInfo)}"
            call.respondText(ok(tsv), ContentType.Application.Json)
        }

        get("/mark") {
            val maxMarks = call.request.queryParameters["max_marks"]?.toIntOrNull() ?: 120
            val interactiveOnly = call.request.queryParameters["interactive_only"]?.toBooleanStrictOrNull() ?: true
            val applyOverlay = call.request.queryParameters["apply_overlay"]?.toBooleanStrictOrNull() ?: false
            val snapshot = collectScreenSnapshot()
            val marks = buildMarks(snapshot.windows, interactiveOnly, maxMarks)
            if (applyOverlay) {
                val enabledResult = overlayManager.setEnabled(
                    true,
                    marks,
                    offsetX = overlayConfig.offsetX,
                    offsetY = overlayConfig.offsetY,
                )
                if (enabledResult.isFailure) {
                    call.respondText(
                        err(enabledResult.exceptionOrNull()?.message ?: "Overlay enable failed"),
                        ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable,
                    )
                    return@get
                }
            }
            val payload = MarkPayload(
                mode = snapshot.mode,
                degraded = snapshot.degraded,
                interactiveOnly = interactiveOnly,
                maxMarks = maxMarks,
                markCount = marks.size,
                overlay = overlayManager.state().toPayload(),
                marks = marks.map { it.toSerializable() },
            )
            call.respondText(ok(json.encodeToString(payload)), ContentType.Application.Json)
        }

        get("/screenshot") {
            val maxDim = call.request.queryParameters["max_dim"]?.toIntOrNull() ?: 700
            val quality = call.request.queryParameters["quality"]?.toIntOrNull() ?: 80
            val annotate = call.request.queryParameters["annotate"]?.toBooleanStrictOrNull() ?: false
            val hideOverlay = call.request.queryParameters["hide_overlay"]?.toBooleanStrictOrNull() ?: !annotate
            val maxMarks = call.request.queryParameters["max_marks"]?.toIntOrNull() ?: 120
            val interactiveOnly = call.request.queryParameters["interactive_only"]?.toBooleanStrictOrNull() ?: true

            val overlayStateBefore = overlayManager.state()
            val marksBefore = overlayManager.currentMarks()
            var temporarilyEnabledByAnnotate = false
            var temporarilyHiddenOverlay = false
            if (annotate) {
                val snapshot = collectScreenSnapshot()
                val marks = buildMarks(snapshot.windows, interactiveOnly, maxMarks)
                val result = overlayManager.setEnabled(
                    true,
                    marks,
                    offsetX = overlayConfig.offsetX,
                    offsetY = overlayConfig.offsetY,
                )
                if (result.isFailure) {
                    call.respondText(
                        err(result.exceptionOrNull()?.message ?: "Overlay annotate failed"),
                        ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable,
                    )
                    return@get
                }
                temporarilyEnabledByAnnotate = !overlayStateBefore.enabled
            }
            if (hideOverlay && overlayManager.state().enabled) {
                val result = overlayManager.setEnabled(false)
                if (result.isSuccess) {
                    temporarilyHiddenOverlay = overlayStateBefore.enabled
                }
            }

            val result = toolRouter.captureScreen(quality = quality, maxWidth = maxDim, maxHeight = maxDim)

            if (temporarilyHiddenOverlay) {
                overlayManager.setEnabled(
                    true,
                    marksBefore,
                    offsetX = overlayConfig.offsetX,
                    offsetY = overlayConfig.offsetY,
                )
            }
            if (temporarilyEnabledByAnnotate && !overlayStateBefore.enabled) {
                overlayManager.setEnabled(false)
            }
            if (result.isSuccess) {
                val data = result.getOrThrow()
                call.respondText(ok(data.data), ContentType.Application.Json)
            } else {
                call.respondText(err(result.exceptionOrNull()?.message ?: "Screenshot failed"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }
    }

    @Serializable data class TapRequest(val x: Float, val y: Float)
    @Serializable data class LongPressRequest(val x: Float, val y: Float, val duration: Long = 500)
    @Serializable data class SwipeRequest(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val duration: Long = 300)
    @Serializable data class ScrollRequest(val direction: String, val amount: String = "medium")

    private fun io.ktor.server.routing.Route.touchRoutes() {
        post("/tap") {
            val req = call.receive<TapRequest>()
            toolRouter.tap(req.x, req.y).respond(call, "Tapped (${req.x}, ${req.y})")
        }
        post("/long-press") {
            val req = call.receive<LongPressRequest>()
            toolRouter.longPress(req.x, req.y, req.duration).respond(call, "Long pressed (${req.x}, ${req.y})")
        }
        post("/double-tap") {
            val req = call.receive<TapRequest>()
            toolRouter.doubleTap(req.x, req.y).respond(call, "Double tapped (${req.x}, ${req.y})")
        }
        post("/swipe") {
            val req = call.receive<SwipeRequest>()
            toolRouter.swipe(req.x1, req.y1, req.x2, req.y2, req.duration).respond(call, "Swiped")
        }
        post("/scroll") {
            val req = call.receive<ScrollRequest>()
            val dir = try { ScrollDirection.valueOf(req.direction.uppercase()) } catch (_: Exception) {
                call.respondText(err("Invalid direction: ${req.direction}"), ContentType.Application.Json, HttpStatusCode.BadRequest); return@post
            }
            val amt = try { ScrollAmount.valueOf(req.amount.uppercase()) } catch (_: Exception) { ScrollAmount.MEDIUM }
            toolRouter.scroll(dir, amt).respond(call, "Scrolled ${req.direction}")
        }
    }

    @Serializable data class KeyRequest(val key_code: Int)

    private fun io.ktor.server.routing.Route.keyRoutes() {
        post("/press/back") {
            toolRouter.pressBack().respond(call, "Pressed Back")
        }
        post("/press/home") {
            toolRouter.pressHome().respond(call, "Pressed Home")
        }
        post("/press/key") {
            val req = call.receive<KeyRequest>()
            toolRouter.pressKey(req.key_code).respond(call, "Pressed key ${req.key_code}")
        }
    }

    @Serializable data class TextRequest(val text: String)

    private fun io.ktor.server.routing.Route.textRoutes() {
        post("/text") {
            val req = call.receive<TextRequest>()
            val success = toolRouter.inputText(req.text)
            if (success) {
                call.respondText(ok("Typed text"), ContentType.Application.Json)
            } else {
                call.respondText(err("Text input failed"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }
    }

    @Serializable data class FindNodesRequest(val by: String, val value: String, val exact_match: Boolean = false)
    @Serializable data class NodeIdRequest(val node_id: String)

    private fun io.ktor.server.routing.Route.nodeRoutes() {
        post("/nodes/find") {
            val req = call.receive<FindNodesRequest>()
            if (!accessibilityProvider.isReady()) {
                call.respondText(err("Accessibility not available"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable); return@post
            }
            val rootNode = accessibilityProvider.getRootNode()
                ?: run { call.respondText(err("No root node"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable); return@post }
            val tree = treeParser.parseTree(rootNode)
            @Suppress("DEPRECATION") rootNode.recycle()
            val by = try { FindBy.valueOf(req.by.uppercase()) } catch (_: Exception) {
                call.respondText(err("Invalid by: ${req.by}"), ContentType.Application.Json, HttpStatusCode.BadRequest); return@post
            }
            val elements = elementFinder.findElements(tree, by, req.value, req.exact_match)
            if (elements.isEmpty()) {
                call.respondText(ok("No nodes found matching ${req.by}='${req.value}'"), ContentType.Application.Json)
            } else {
                val text = elements.joinToString("\n") { e ->
                    "${e.id}\t${e.className}\ttext=${e.text}\tdesc=${e.contentDescription}\tres=${e.resourceId}\tbounds=${e.bounds}"
                }
                call.respondText(ok("Found ${elements.size} node(s):\n$text"), ContentType.Application.Json)
            }
        }

        post("/nodes/click") {
            val req = call.receive<NodeIdRequest>()
            if (!accessibilityProvider.isReady()) {
                call.respondText(err("Accessibility not available"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable); return@post
            }
            val rootNode = accessibilityProvider.getRootNode()
                ?: run { call.respondText(err("No root node"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable); return@post }
            val tree = treeParser.parseTree(rootNode)
            @Suppress("DEPRECATION") rootNode.recycle()
            val node = elementFinder.findNodeById(tree, req.node_id)
            if (node == null) {
                call.respondText(err("Node not found: ${req.node_id}"), ContentType.Application.Json, HttpStatusCode.NotFound); return@post
            }
            val b = node.bounds
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f
            toolRouter.tap(cx, cy).respond(call, "Clicked node ${req.node_id}")
        }
    }

    @Serializable
    data class OverlayRequest(
        val enabled: Boolean,
        val max_marks: Int = 300,
        val interactive_only: Boolean = false,
        val auto_refresh: Boolean = true,
        val refresh_interval_ms: Long = 800L,
        val offset_x: Int? = null,
        val offset_y: Int? = null,
    )

    data class OverlayConfig(
        val enabled: Boolean = false,
        val maxMarks: Int = 300,
        val interactiveOnly: Boolean = false,
        val autoRefresh: Boolean = true,
        val refreshIntervalMs: Long = 800L,
        val offsetX: Int = 0,
        val offsetY: Int = 0,
    )

    @Serializable
    data class OverlayStatePayload(
        val available: Boolean,
        val enabled: Boolean,
        val markCount: Int,
        val autoRefresh: Boolean,
        val refreshIntervalMs: Long,
        val offsetX: Int,
        val offsetY: Int,
    )

    @Serializable
    data class SerializableMark(
        val index: Int,
        val label: String,
        val bounds: String,
        val node_id: String,
        val class_name: String? = null,
        val text: String? = null,
        val desc: String? = null,
        val res_id: String? = null,
    )

    @Serializable
    data class MarkPayload(
        val mode: ToolRouter.Mode,
        val degraded: Boolean,
        val interactiveOnly: Boolean,
        val maxMarks: Int,
        val markCount: Int,
        val overlay: OverlayStatePayload,
        val marks: List<SerializableMark>,
    )

    private fun io.ktor.server.routing.Route.overlayRoutes() {
        get("/overlay") {
            val state = overlayManager.state()
            call.respondText(ok(json.encodeToString(state.toPayload())), ContentType.Application.Json)
        }
        post("/overlay") {
            val req = call.receive<OverlayRequest>()
            if (!req.enabled) {
                stopOverlayAutoRefresh()
                val result = overlayManager.setEnabled(false)
                if (result.isFailure) {
                    call.respondText(
                        err(result.exceptionOrNull()?.message ?: "Overlay disable failed"),
                        ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable,
                    )
                    return@post
                }
                overlayConfig = overlayConfig.copy(enabled = false)
                call.respondText(
                    ok(json.encodeToString(overlayManager.state().toPayload())),
                    ContentType.Application.Json,
                )
                return@post
            }
            val snapshot = collectScreenSnapshot()
            val marks = buildMarks(snapshot.windows, req.interactive_only, req.max_marks)
            val resolvedOffsetX = req.offset_x ?: overlayConfig.offsetX
            val resolvedOffsetY = req.offset_y ?: overlayConfig.offsetY
            val result = overlayManager.setEnabled(
                true,
                marks,
                offsetX = resolvedOffsetX,
                offsetY = resolvedOffsetY,
            )
            if (result.isFailure) {
                call.respondText(
                    err(result.exceptionOrNull()?.message ?: "Overlay enable failed"),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            overlayConfig = OverlayConfig(
                enabled = true,
                maxMarks = req.max_marks.coerceAtLeast(1),
                interactiveOnly = req.interactive_only,
                autoRefresh = req.auto_refresh,
                refreshIntervalMs = req.refresh_interval_ms.coerceIn(200L, 5_000L),
                offsetX = resolvedOffsetX,
                offsetY = resolvedOffsetY,
            )
            if (overlayConfig.autoRefresh) {
                startOverlayAutoRefresh()
            } else {
                stopOverlayAutoRefresh()
            }
            call.respondText(
                ok(json.encodeToString(overlayManager.state().toPayload())),
                ContentType.Application.Json,
            )
        }
    }

    private data class ScreenSnapshot(
        val mode: ToolRouter.Mode,
        val screenInfo: com.example.amctl.services.accessibility.ScreenInfo,
        val windows: List<WindowData>,
        val degraded: Boolean,
    )

    private fun collectScreenSnapshot(): ScreenSnapshot {
        val screenInfo = toolRouter.getScreenInfo()
        val windows = mutableListOf<WindowData>()
        var degraded = false

        if (accessibilityProvider.isReady()) {
            val accessibilityWindows = accessibilityProvider.getAccessibilityWindows()
            if (accessibilityWindows.isNotEmpty()) {
                for (window in accessibilityWindows) {
                    val root = window.root ?: continue
                    val tree = treeParser.parseTree(root, rootParentId = "root_w${window.id}")
                    windows.add(
                        WindowData(
                            windowId = window.id,
                            windowType = AccessibilityTreeParser.mapWindowType(window.type),
                            packageName = root.packageName?.toString(),
                            title = window.title?.toString(),
                            layer = window.layer,
                            focused = window.isFocused,
                            tree = tree,
                        ),
                    )
                    @Suppress("DEPRECATION") root.recycle()
                }
            } else {
                val rootNode = accessibilityProvider.getRootNode()
                if (rootNode != null) {
                    degraded = true
                    windows.add(
                        WindowData(
                            windowId = 0,
                            windowType = "APPLICATION",
                            packageName = rootNode.packageName?.toString(),
                            focused = true,
                            tree = treeParser.parseTree(rootNode),
                        ),
                    )
                    @Suppress("DEPRECATION") rootNode.recycle()
                }
            }
        } else {
            degraded = true
        }

        return ScreenSnapshot(
            mode = toolRouter.currentMode,
            screenInfo = screenInfo,
            windows = windows,
            degraded = degraded,
        )
    }

    private fun buildMarks(
        windows: List<WindowData>,
        interactiveOnly: Boolean,
        maxMarks: Int,
    ): List<OverlayMark> {
        val out = mutableListOf<OverlayMark>()
        for (window in windows) {
            if (window.windowType == "ACCESSIBILITY_OVERLAY") {
                continue
            }
            collectMarksRecursive(window.tree, interactiveOnly, out)
        }
        val sorted = out.sortedWith(
            compareBy<OverlayMark> { if (it.interactive) 1 else 0 }
                .thenByDescending { area(it.bounds) },
        )
        return sorted
            .take(maxMarks.coerceAtLeast(1))
            .mapIndexed { idx, mark ->
                val prefix = if (mark.interactive) "C" else "T"
                mark.copy(index = idx + 1, label = "${prefix}${idx + 1}")
            }
    }

    private fun collectMarksRecursive(
        node: com.example.amctl.services.accessibility.AccessibilityNodeData,
        interactiveOnly: Boolean,
        out: MutableList<OverlayMark>,
    ) {
        val isInteractive = node.clickable || node.longClickable || node.editable || node.scrollable || node.focusable
        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val shouldInclude = if (interactiveOnly) isInteractive else isInteractive || hasText
        if (shouldInclude && node.visible && area(node.bounds) > 0) {
            out.add(
                OverlayMark(
                    index = 0,
                    label = "",
                    interactive = isInteractive,
                    bounds = node.bounds,
                    nodeId = node.id,
                    className = node.className,
                    text = node.text,
                    desc = node.contentDescription,
                    resId = node.resourceId,
                ),
            )
        }
        for (child in node.children) {
            collectMarksRecursive(child, interactiveOnly, out)
        }
    }

    private fun area(bounds: com.example.amctl.services.accessibility.BoundsData): Int {
        val w = (bounds.right - bounds.left).coerceAtLeast(0)
        val h = (bounds.bottom - bounds.top).coerceAtLeast(0)
        return w * h
    }

    private fun OverlayMark.toSerializable(): SerializableMark = SerializableMark(
        index = index,
        label = label,
        bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
        node_id = nodeId,
        class_name = className,
        text = text,
        desc = desc,
        res_id = resId,
    )

    private fun OverlayState.toPayload(): OverlayStatePayload = OverlayStatePayload(
        available = available,
        enabled = enabled,
        markCount = markCount,
        autoRefresh = overlayConfig.autoRefresh && overlayConfig.enabled,
        refreshIntervalMs = overlayConfig.refreshIntervalMs,
        offsetX = overlayConfig.offsetX,
        offsetY = overlayConfig.offsetY,
    )

    @Synchronized
    private fun startOverlayAutoRefresh() {
        stopOverlayAutoRefresh()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        overlayScheduler = scheduler
        val interval = overlayConfig.refreshIntervalMs.coerceIn(200L, 5_000L)
        scheduler.scheduleAtFixedRate(
            {
                try {
                    if (!overlayConfig.enabled) return@scheduleAtFixedRate
                    val snapshot = collectScreenSnapshot()
                    val marks = buildMarks(
                        windows = snapshot.windows,
                        interactiveOnly = overlayConfig.interactiveOnly,
                        maxMarks = overlayConfig.maxMarks,
                    )
                    overlayManager.updateMarks(marks)
                } catch (_: Exception) {
                }
            },
            interval,
            interval,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    private fun stopOverlayAutoRefresh() {
        overlayScheduler?.shutdownNow()
        overlayScheduler = null
    }

    @Serializable data class LaunchRequest(val package_name: String)
    @Serializable data class StopRequest(val package_name: String)
    @Serializable data class ShellRequest(val command: String)
    @Serializable data class IntentRequest(
        val action: String? = null,
        val data: String? = null,
        val package_name: String? = null,
        val component: String? = null,
        val extras: Map<String, String>? = null,
    )

    private fun io.ktor.server.routing.Route.appRoutes() {
        post("/app/launch") {
            val req = call.receive<LaunchRequest>()
            val result = toolRouter.appController.launch(req.package_name)
            if (result.isSuccess) {
                call.respondText(ok(result.getOrThrow()), ContentType.Application.Json)
            } else {
                call.respondText(err(result.exceptionOrNull()?.message ?: "Launch failed"), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        post("/app/stop") {
            val req = call.receive<StopRequest>()
            toolRouter.appController.forceStop(req.package_name).respond(call, "Stopped ${req.package_name}")
        }

        get("/app/top") {
            val top = toolRouter.appController.getTopActivity()
            if (top != null) {
                call.respondText(ok(top), ContentType.Application.Json)
            } else {
                call.respondText(err("Could not determine top activity"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        get("/packages") {
            val filter = call.request.queryParameters["filter"]
            val includeSystem = call.request.queryParameters["include_system"]?.toBoolean() ?: false
            val result = toolRouter.appController.listPackages(filter = filter, thirdPartyOnly = !includeSystem)
            if (result.isSuccess) {
                call.respondText(ok(result.getOrThrow().joinToString("\n")), ContentType.Application.Json)
            } else {
                call.respondText(err(result.exceptionOrNull()?.message ?: "Failed"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        post("/shell") {
            val req = call.receive<ShellRequest>()
            val result = toolRouter.appController.execShell(req.command)
            if (result.isSuccess) {
                call.respondText(ok(result.getOrThrow().ifBlank { "(no output)" }), ContentType.Application.Json)
            } else {
                call.respondText(err(result.exceptionOrNull()?.message ?: "Shell failed"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        post("/intent") {
            val req = call.receive<IntentRequest>()
            if (req.action == null && req.data == null && req.component == null) {
                call.respondText(err("At least one of action, data, or component required"), ContentType.Application.Json, HttpStatusCode.BadRequest); return@post
            }
            val result = toolRouter.appController.startIntent(
                action = req.action, dataUri = req.data, packageName = req.package_name, component = req.component, extras = req.extras,
            )
            if (result.isSuccess) {
                call.respondText(ok(result.getOrThrow()), ContentType.Application.Json)
            } else {
                call.respondText(err(result.exceptionOrNull()?.message ?: "Intent failed"), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
    }

    private suspend fun Result<Unit>.respond(call: io.ktor.server.application.ApplicationCall, successMsg: String) {
        if (isSuccess) {
            call.respondText(ok(successMsg), ContentType.Application.Json)
        } else {
            call.respondText(err(exceptionOrNull()?.message ?: "Failed"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }
}
