package com.example.amctl.services.system

interface AppController {
    fun launch(packageName: String): Result<String>
    fun forceStop(packageName: String): Result<Unit>
    fun getTopActivity(): String?
}
