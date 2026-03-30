package com.memohai.autofish.core

sealed class ToolException(message: String) : RuntimeException(message) {
    class PermissionDenied(message: String) : ToolException(message)
    class InvalidParams(message: String) : ToolException(message)
    class ActionFailed(message: String) : ToolException(message)
    class NodeNotFound(message: String) : ToolException(message)
}
