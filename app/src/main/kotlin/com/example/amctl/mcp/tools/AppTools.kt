package com.example.amctl.mcp.tools

import com.example.amctl.services.system.ToolRouter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object AppTools {
    fun register(server: Server, toolRouter: ToolRouter) {
        registerLaunchApp(server, toolRouter)
        registerForceStopApp(server, toolRouter)
        registerGetTopActivity(server, toolRouter)
        registerListPackages(server, toolRouter)
        registerShell(server, toolRouter)
        registerStartIntent(server, toolRouter)
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

    private fun registerListPackages(server: Server, router: ToolRouter) {
        server.addTool(
            name = "amctl_list_packages",
            description = "List installed packages. Optionally filter by keyword. Returns third-party apps by default.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("filter", buildJsonObject {
                        put("type", "string")
                        put("description", "Keyword to filter package names (case-insensitive)")
                    })
                    put("include_system", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include system packages (default: false, only third-party apps)")
                    })
                },
            ),
        ) { request ->
            if (!router.isV2Available) {
                return@addTool errorResult("Shizuku not available — list_packages requires Shizuku")
            }
            val filter = request.arguments?.get("filter")?.jsonPrimitive?.content
            val includeSystem = request.arguments?.get("include_system")?.jsonPrimitive?.boolean ?: false

            val result = router.appController.listPackages(
                filter = filter,
                thirdPartyOnly = !includeSystem,
            )
            if (result.isSuccess) {
                val packages = result.getOrThrow()
                CallToolResult(
                    content = listOf(TextContent(text = packages.joinToString("\n").ifBlank { "No packages found" })),
                )
            } else {
                errorResult(result.exceptionOrNull()?.message ?: "Failed to list packages")
            }
        }
    }

    private fun registerShell(server: Server, router: ToolRouter) {
        server.addTool(
            name = "amctl_shell",
            description = "Execute an arbitrary shell command on the device via Shizuku (runs as shell/adb UID). Returns stdout.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "The shell command to execute")
                    })
                },
                required = listOf("command"),
            ),
        ) { request ->
            if (!router.isV2Available) {
                return@addTool errorResult("Shizuku not available — shell requires Shizuku")
            }
            val command = request.arguments?.get("command")?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing command")

            val result = router.appController.execShell(command)
            if (result.isSuccess) {
                CallToolResult(
                    content = listOf(TextContent(text = result.getOrThrow().ifBlank { "(no output)" })),
                )
            } else {
                errorResult(result.exceptionOrNull()?.message ?: "Shell command failed")
            }
        }
    }

    private fun registerStartIntent(server: Server, router: ToolRouter) {
        server.addTool(
            name = "amctl_start_intent",
            description = "Start an Activity via intent. Supports action, data URI, package, component, and string extras.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "Intent action (e.g. android.intent.action.VIEW)")
                    })
                    put("data", buildJsonObject {
                        put("type", "string")
                        put("description", "Data URI (e.g. https://www.bing.com)")
                    })
                    put("package_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Target package name (optional)")
                    })
                    put("component", buildJsonObject {
                        put("type", "string")
                        put("description", "Component name (e.g. com.example/.MainActivity)")
                    })
                    put("extras", buildJsonObject {
                        put("type", "object")
                        put("description", "String extras as key-value pairs")
                    })
                },
            ),
        ) { request ->
            if (!router.isV2Available) {
                return@addTool errorResult("Shizuku not available — start_intent requires Shizuku")
            }
            val args = request.arguments
            val action = args?.get("action")?.jsonPrimitive?.content
            val dataUri = args?.get("data")?.jsonPrimitive?.content
            val packageName = args?.get("package_name")?.jsonPrimitive?.content
            val component = args?.get("component")?.jsonPrimitive?.content
            val extrasObj = args?.get("extras")?.let { if (it is JsonObject) it else null }
            val extras = extrasObj?.mapValues { it.value.jsonPrimitive.content }

            if (action == null && dataUri == null && component == null) {
                return@addTool errorResult("At least one of action, data, or component is required")
            }

            val result = router.appController.startIntent(
                action = action,
                dataUri = dataUri,
                packageName = packageName,
                component = component,
                extras = extras,
            )
            if (result.isSuccess) {
                CallToolResult(
                    content = listOf(TextContent(text = result.getOrThrow())),
                )
            } else {
                errorResult(result.exceptionOrNull()?.message ?: "Intent failed")
            }
        }
    }
}
