package com.openlauncher.app.projection.taskhost

import android.content.Intent
import android.os.*
import android.view.MotionEvent
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow

object TaskHostState {
    const val DESCRIPTOR = "com.openlauncher.app.taskhost.v1"
    @Volatile var surface: Surface? = null
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var density = 160
    @Volatile var token = 0L
    @Volatile var intent: Intent? = null
    @Volatile var callback: IBinder? = null
    val message = MutableStateFlow("")
    fun touch(event: MotionEvent): Boolean {
        val target = callback ?: return false
        val p = Parcel.obtain()
        return try { p.writeInterfaceToken(DESCRIPTOR); event.writeToParcel(p, 0); target.transact(1, p, null, IBinder.FLAG_ONEWAY) }
        finally { p.recycle() }
    }
    fun close(owner: Long) {
        if (owner != token) return
        callback?.let { target ->
            val p = Parcel.obtain()
            try { p.writeInterfaceToken(DESCRIPTOR); target.transact(2, p, null, IBinder.FLAG_ONEWAY) }
            catch (_: Exception) {} finally { p.recycle() }
        }
        callback = null; surface?.release(); surface = null; intent = null; token++
    }
}

class TaskHostEndpoint : Binder() {
    private val main = Handler(Looper.getMainLooper())
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (getCallingUid() != 2000) throw SecurityException("ADB shell required")
        data.enforceInterface(TaskHostState.DESCRIPTOR)
        val owner = data.readLong()
        check(owner == TaskHostState.token && TaskHostState.surface?.isValid == true) { "Projection session closed" }
        when (code) {
            1 -> {
                reply!!.writeNoException(); TaskHostState.surface!!.writeToParcel(reply, 0)
                reply.writeInt(TaskHostState.width); reply.writeInt(TaskHostState.height)
                reply.writeInt(TaskHostState.density)
                checkNotNull(TaskHostState.intent).writeToParcel(reply, 0)
            }
            2 -> {
                val callback = data.readStrongBinder()
                callback.linkToDeath({ main.post {
                    if (TaskHostState.token == owner) {
                        TaskHostState.callback = null
                        TaskHostState.message.value = "Android Auto helper stopped. Close and reopen Android Auto."
                    }
                } }, 0)
                main.post { if (TaskHostState.token == owner) {
                    TaskHostState.callback = callback
                    TaskHostState.message.value = ""
                } else {
                    val p = Parcel.obtain()
                    try { p.writeInterfaceToken(TaskHostState.DESCRIPTOR); callback.transact(2, p, null, IBinder.FLAG_ONEWAY) }
                    finally { p.recycle() }
                } }
                reply!!.writeNoException()
            }
            3 -> {
                val message = data.readString().orEmpty()
                main.post { if (TaskHostState.token == owner) TaskHostState.message.value = message }
                reply!!.writeNoException()
            }
            else -> return false
        }
        return true
    }
}
