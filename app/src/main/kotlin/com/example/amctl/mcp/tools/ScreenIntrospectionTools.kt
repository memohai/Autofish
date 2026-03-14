package com.example.amctl.mcp.tools

import com.example.amctl.services.accessibility.AccessibilityServiceProvider
import com.example.amctl.services.accessibility.AccessibilityTreeParser
import com.example.amctl.services.accessibility.CompactTreeFormatter
import com.example.amctl.services.accessibility.MultiWindowResult
import com.example.amctl.services.accessibility.WindowData
import com.example.amctl.services.system.ToolRouter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Suppress("LongMethod")
object ScreenIntrospectionTools {
    private const val MAX_SCREENSHOT_DIM = 700

    fun register(
        server: Server,
        accessibilityProvider: AccessibilityServiceProvider,
        treeParser: AccessibilityTreeParser,
        formatter: CompactTreeFormatter,
        toolRouter: ToolRouter,
    ) {
        server.addTool(
            name = "amctl_get_screen_state",
            description = "Get the current screen state: app info, screen size, and compact TSV UI node listing",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("include_screenshot", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include a low-resolution screenshot (default: false)")
                    })
                },
            ),
        ) { request ->
            val includeScreenshot = request.arguments?.get("include_screenshot")?.jsonPrimitive?.boolean ?: false

            if (!accessibilityProvider.isReady() && !toolRouter.isV2Available) {
                return@addTool CallToolResult(content = listOf(TextContent(text = "Neither Accessibility nor Shizuku available")), isError = true)
            }

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
                            WindowData(windowId = 0, windowType = "APPLICATION", packageName = rootNode.packageName?.toString(), focused = true, tree = treeParser.parseTree(rootNode)),
                        )
                        @Suppress("DEPRECATION") rootNode.recycle()
                    }
                }
            } else {
                degraded = true
            }

            val modeHeader = "[mode: ${toolRouter.currentMode}]"
            val result = MultiWindowResult(windows = windows, degraded = degraded)
            val tsv = "$modeHeader\n${formatter.formatMultiWindow(result, screenInfo)}"

            if (includeScreenshot) {
                val screenshotResult = toolRouter.captureScreen(
                    maxWidth = MAX_SCREENSHOT_DIM,
                    maxHeight = MAX_SCREENSHOT_DIM,
                )
                if (screenshotResult.isSuccess) {
                    val data = screenshotResult.getOrThrow()
                    CallToolResult(content = listOf(TextContent(text = tsv), ImageContent(data = data.data, mimeType = "image/jpeg")))
                } else {
                    CallToolResult(content = listOf(TextContent(text = tsv)))
                }
            } else {
                CallToolResult(content = listOf(TextContent(text = tsv)))
            }
        }
    }
}
