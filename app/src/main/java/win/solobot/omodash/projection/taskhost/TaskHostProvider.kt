package win.solobot.omodash.projection.taskhost

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*

/** Binder rendezvous for the local shell helper. Also protected by DUMP in the manifest. */
class TaskHostProvider : ContentProvider() {
    private val endpoint by lazy { TaskHostEndpoint() }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (Binder.getCallingUid() != 2000) throw SecurityException("ADB shell required")
        check(method == "bridge" && arg?.toLongOrNull() == TaskHostState.token && TaskHostState.surface?.isValid == true) { "No matching projection session" }
        return Bundle().apply { putBinder("bridge", endpoint) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
