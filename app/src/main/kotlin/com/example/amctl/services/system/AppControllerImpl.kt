package com.example.amctl.services.system

import android.util.Log
import javax.inject.Inject

class AppControllerImpl
    @Inject
    constructor(
        private val shizukuProvider: ShizukuProvider,
    ) : AppController {

        override fun launch(packageName: String): Result<String> = try {
            val resolveOutput = shizukuProvider.exec(
                "cmd package resolve-activity --brief $packageName | tail -1",
            ).trim()

            if (resolveOutput.isBlank() || resolveOutput.contains("No activity")) {
                Result.failure(IllegalArgumentException("No launchable activity for $packageName"))
            } else {
                shizukuProvider.exec("am start -n $resolveOutput")
                Result.success(resolveOutput)
            }
        } catch (e: Exception) {
            Log.w(TAG, "launch failed: $packageName", e)
            Result.failure(e)
        }

        override fun forceStop(packageName: String): Result<Unit> = try {
            shizukuProvider.exec("am force-stop $packageName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "forceStop failed: $packageName", e)
            Result.failure(e)
        }

        override fun getTopActivity(): String? = try {
            val output = shizukuProvider.exec(
                "dumpsys activity activities | grep -E 'topResumedActivity=|ResumedActivity:|mResumedActivity=' | head -1",
            ).trim()
            if (output.isNotBlank()) {
                ACTIVITY_PATTERN.find(output)?.groupValues?.get(1) ?: output
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "getTopActivity failed", e)
            null
        }

        companion object {
            private const val TAG = "amctl:AppCtrl"
            private val ACTIVITY_PATTERN = Regex("""\s(\S+/\S+)\s""")
        }
    }
