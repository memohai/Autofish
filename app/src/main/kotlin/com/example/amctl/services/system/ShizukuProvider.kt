package com.example.amctl.services.system

interface ShizukuProvider {
    fun isAvailable(): Boolean
    fun isInstalled(): Boolean
    fun hasPermission(): Boolean
    fun exec(command: String): String
    fun execBytes(command: String): ByteArray
    fun requestPermission(requestCode: Int)
    fun addPermissionResultListener(listener: (Int, Int) -> Unit)
    fun removePermissionResultListener(listener: (Int, Int) -> Unit)
}
