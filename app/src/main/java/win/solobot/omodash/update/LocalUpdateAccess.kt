package win.solobot.omodash.update

import android.content.Context
import android.os.Process
import android.os.SystemClock
import win.solobot.omodash.projection.taskhost.LocalAdb

/** Explicit Install update action, using the same locally authorized adbd as AA. */
internal object LocalUpdateAccess {
    fun allow(context: Context) {
        val pkg = context.packageName
        require(pkg.matches(Regex("[a-zA-Z0-9_.]+")))
        val user = Process.myUid() / 100_000
        val deadline = SystemClock.elapsedRealtime() + 15_000
        LocalAdb(context).use { adb ->
            adb.run("appops set --user $user $pkg REQUEST_INSTALL_PACKAGES allow",
                active = { SystemClock.elapsedRealtime() < deadline }, output = {})
        }
    }
}
