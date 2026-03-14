package com.example.amctl.mcp.tools

import com.example.amctl.services.system.ToolRouter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject

object SystemActionTools {
    fun register(server: Server, toolRouter: ToolRouter) {
        val emptySchema = ToolSchema(properties = buildJsonObject {})

        server.addTool(name = "amctl_press_back", description = "Press the Back button", inputSchema = emptySchema) {
            toolRouter.pressBack().toCallToolResult("Pressed Back")
        }

        server.addTool(name = "amctl_press_home", description = "Press the Home button", inputSchema = emptySchema) {
            toolRouter.pressHome().toCallToolResult("Pressed Home")
        }
    }
}
