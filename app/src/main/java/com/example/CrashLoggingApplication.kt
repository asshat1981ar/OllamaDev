package com.example

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class CrashLoggingApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(throwable)
            } catch (e: Throwable) {
                Log.e("CrashLoggingApplication", "Failed to write crash log", e)
            }
            previousHandler?.uncaughtException(thread, throwable)
                ?: run {
                    Process.killProcess(Process.myPid())
                    exitProcess(1)
                }
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val content = buildString {
            appendLine("Crash time: $timestamp")
            appendLine("Android version: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(sw.toString())
        }

        // Primary: internal app storage, always writable, no permission needed.
        try {
            filesDir.resolve("crash_$timestamp.txt").writeText(content)
        } catch (e: Throwable) {
            Log.e("CrashLoggingApplication", "Failed to write internal crash log", e)
        }

        // Secondary: public Downloads folder via MediaStore, readable from outside the app sandbox.
        try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "ollamadev_crash_$timestamp.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            }
        } catch (e: Throwable) {
            Log.e("CrashLoggingApplication", "Failed to write public crash log", e)
        }
    }
}
