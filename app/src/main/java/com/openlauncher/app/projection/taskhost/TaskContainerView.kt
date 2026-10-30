package com.openlauncher.app.projection.taskhost

import android.graphics.SurfaceTexture
import android.os.Build
import android.os.SystemClock
import android.view.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openlauncher.app.diagnostics.DiagnosticLog
import com.openlauncher.app.util.AppLaunchIntent
import kotlinx.coroutines.*

@Composable
fun TaskContainerView(packageName: String, modifier: Modifier = Modifier) {
    val message by TaskHostState.message.collectAsState()
    val scope = rememberCoroutineScope()
    Box(modifier.background(Color.Black)) {
        key(packageName) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                object : TextureView(context), TextureView.SurfaceTextureListener {
                    var owner = 0L
                    var job: Job? = null
                    var adb: LocalAdb? = null
                    init { surfaceTextureListener = this }
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        release()
                        owner = SystemClock.elapsedRealtimeNanos()
                        val session = owner
                        TaskHostState.surface = Surface(texture)
                        TaskHostState.width = width; TaskHostState.height = height
                        TaskHostState.density = context.resources.displayMetrics.densityDpi
                        TaskHostState.token = session
                        TaskHostState.message.value = "Connecting Android Auto…"
                        job = scope.launch(Dispatchers.IO) {
                            try {
                                check(Build.VERSION.SDK_INT in 29..30) { "Embedded Android Auto is supported on Android 10 / 11 head units" }
                                val intent = checkNotNull(AppLaunchIntent.resolve(context, packageName)) { "Android Auto app is not installed or has no screen" }
                                val client = LocalAdb(context)
                                try {
                                    withContext(Dispatchers.Main) {
                                        if (TaskHostState.token == session) {
                                            TaskHostState.intent = intent
                                            adb = client
                                        }
                                    }
                                } catch (e: Exception) { client.close(); throw e }
                                if (!isActive || TaskHostState.token != session) { client.close(); return@launch }
                                val quote: (String) -> String = { "'" + it.replace("'", "'\\''") + "'" }
                                val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} exec app_process /system/bin " +
                                    "com.openlauncher.app.projection.taskhost.TaskHostShell ${quote(context.packageName)} $session"
                                client.use {
                                    it.run(command, { isActive && TaskHostState.token == session }) { output ->
                                        DiagnosticLog.event("AndroidAuto", output.take(4096))
                                    }
                                }
                                if (isActive && TaskHostState.token == session && TaskHostState.callback == null && TaskHostState.message.value == "Connecting Android Auto…")
                                    TaskHostState.message.value = "Android Auto helper stopped. Close and reopen Android Auto."
                            } catch (e: Exception) {
                                if (isActive && TaskHostState.token == session) {
                                    TaskHostState.message.value = when (e) {
                                        is java.net.ConnectException -> "Local ADB is unavailable. Complete Android Auto USB setup, then reopen Android Auto."
                                        is java.net.SocketTimeoutException -> "ADB authorization timed out. Accept the head unit's USB debugging prompt, then reopen Android Auto."
                                        else -> e.message ?: "Cannot start Android Auto"
                                    }
                                    DiagnosticLog.event("AndroidAuto", "Local helper failed", e)
                                }
                            }
                        }
                    }
                    override fun onTouchEvent(event: MotionEvent): Boolean = runCatching { TaskHostState.touch(event) }.getOrDefault(false)
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        onSurfaceTextureAvailable(texture, width, height)
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    fun release() {
                        job?.cancel(); job = null
                        TaskHostState.close(owner)
                        adb?.close(); adb = null
                    }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }
                    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
                }
            })
        }
        if (message.isNotBlank()) Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f)).padding(24.dp))
    }
}
