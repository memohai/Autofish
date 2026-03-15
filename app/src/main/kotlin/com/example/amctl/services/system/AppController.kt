package com.example.amctl.services.system

interface AppController {
    fun launch(packageName: String): Result<String>
    fun forceStop(packageName: String): Result<Unit>
    fun getTopActivity(): String?
    fun listPackages(filter: String? = null, thirdPartyOnly: Boolean = true): Result<List<String>>
    fun execShell(command: String): Result<String>
    fun startIntent(
        action: String? = null,
        dataUri: String? = null,
        packageName: String? = null,
        component: String? = null,
        extras: Map<String, String>? = null,
    ): Result<String>
}
