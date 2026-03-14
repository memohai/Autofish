@file:Suppress("PrivateApi")

package com.example.amctl.services.system

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import javax.inject.Inject

class ShizukuProviderImpl
    @Inject
    constructor() : ShizukuProvider {

        private val newProcessMethod by lazy {
            try {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                ).also { it.isAccessible = true }
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku.newProcess method not found", e)
                null
            }
        }

        override fun isAvailable(): Boolean = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

        override fun isInstalled(): Boolean = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

        override fun hasPermission(): Boolean = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

        override fun exec(command: String): String {
            val process = startProcess(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return output
        }

        override fun execBytes(command: String): ByteArray {
            val process = startProcess(arrayOf("sh", "-c", command))
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            return bytes
        }

        override fun requestPermission(requestCode: Int) {
            Shizuku.requestPermission(requestCode)
        }

        override fun addPermissionResultListener(listener: (Int, Int) -> Unit) {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                listener(requestCode, grantResult)
            }
        }

        override fun removePermissionResultListener(listener: (Int, Int) -> Unit) {
            // Shizuku listener removal requires the same instance;
            // callers should manage their own listener lifecycle
        }

        private fun startProcess(cmd: Array<String>): Process {
            val method = newProcessMethod
                ?: throw RuntimeException("Shizuku.newProcess not available")
            return try {
                method.invoke(null, cmd, null as Array<String>?, null as String?) as Process
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku.newProcess invocation failed", e)
                throw RuntimeException("Shizuku exec failed: ${e.message}", e)
            }
        }

        companion object {
            private const val TAG = "amctl:Shizuku"
        }
    }
