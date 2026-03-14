package com.example.amctl.mcp.tools

import com.example.amctl.services.system.ToolRouter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object AppTools {
    fun register(server: Server, toolRouter: ToolRouter) {
        registerLaunchApp(server, toolRouter)
        registerForceStopApp(server, toolRouter)
        registerGetTopActivity(server, toolRouter)
    }

    private fun registerLaunchApp(server: Server, router: ToolRouter) {
        server.addTool(
            name = "amctl_launch_app",
            description = "Launch an app by package name",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("package_name", buildJsonObject { put("type", "string") })
                },
                required = listOf("package_name"),
            ),
        ) { request ->
            if (!router.isV2Available) {
                return@addTool errorResult("Shizuku not available — launch_app requires shell permission")
            }
            val packageName = request.arguments?.get("package_name")?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing package_name")

            val result = router.appController.launch(packageName)
            if (result.isSuccess) {
                CallToolResult(
                    content = listOf(TextContent(text = """{"success":true,"activity":"${result.getOrThrow()}"}""")),
                )
            } else {
                errorResult(result.exceptionOrNull()?.message ?: "Launch failed")
            }
        }
    }

    private fun registerForceStopApp(server: Server, router: ToolRouter) {
        server.addTool(
            name = "amctl_force_stop_app",
            description = "Force stop an app by package name",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("package_name", buildJsonObject { put("type", "string") })
                },
                required = listOf("package_name"),
            ),
        ) { request ->
            if (!router.isV2Available) {
                return@addTool errorResult("Shizuku not available — force_stop_app requires shell permission")
            }
            val packageName = request.arguments?.get("package_name")?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing package_name")

            val result = router.appController.forceStop(packageName)
            result.toCallToolResult("""{"success":true}""")
        }
    }

    private fun registerGetTopActivity(server: Server, router: ToolRouter) {
        server.addTool(
            name = "amctl_get_top_activity",
            description = "Get the currently active (top) activity",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            if (!router.isV2Available) {
                return@addTool errorResult("Shizuku not available — get_top_activity requires shell permission")
            }

            val topActivity = router.appController.getTopActivity()
            if (topActivity != null) {
                val parts = topActivity.split("/")
                val pkg = parts.firstOrNull() ?: ""
                CallToolResult(
                    content = listOf(TextContent(text = """{"activity":"$topActivity","package":"$pkg"}""")),
                )
            } else {
                errorResult("Could not determine top activity")
            }
        }
    }
}
