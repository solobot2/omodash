package win.solobot.omodash.update

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import win.solobot.omodash.BuildConfig

/** Confirms only the install session opened by our verified self-update flow. */
class UpdateAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        scheduleCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() !in UpdateInstaller.installerPackages) return
        scheduleCheck()
    }

    private fun scheduleCheck() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            val store = UpdateStore(this)
            if (!store.confirmationArmed()) return@postDelayed
            confirmOwnUpdate()
            // Window events can arrive before the dialog nodes are ready, or
            // while Accessibility reconnects. Retry only within the armed window.
            if (store.confirmationArmed()) scheduleCheck()
        }, 400)
    }

    private fun confirmOwnUpdate() {
        val root = rootInActiveWindow ?: run { diagnostic("Installer root is not available yet"); return }
        val installerPackage = root.packageName?.toString() ?: return
        val store = UpdateStore(this)
        if (!store.mayConfirm(installerPackage)) { diagnostic("Waiting for expected installer window"); return }
        val session = packageManager.packageInstaller.getSessionInfo(store.sessionId) ?: return
        if (session.appPackageName != packageName || session.installerPackageName != packageName) {
            diagnostic("Install session owner does not match"); return
        }
        val label = applicationInfo.loadLabel(packageManager).toString()
        val ownLabelVisible = root.findAccessibilityNodeInfosByText(label).any {
            it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }
        if (!ownLabelVisible) { diagnostic("Own application label is not visible yet"); return }
        val candidates = root.findAccessibilityNodeInfosByViewId("android:id/button1") +
            confirmLabels.flatMap { root.findAccessibilityNodeInfosByText(it) }
        val button = candidates.firstOrNull {
            it.isEnabled && it.isVisibleToUser && it.isClickable &&
                it.text?.toString()?.trim()?.lowercase() in confirmLabels
        } ?: run { diagnostic("An enabled Install/Update button is not visible yet"); return }
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            store.disarm()
            Log.i("OmodashUpdater", "Confirmed verified self-update session ${store.sessionId}")
        }
    }

    private fun diagnostic(message: String) {
        if (BuildConfig.UPDATE_TEST_MODE) Log.d("OmodashUpdater", message)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        // Never click generic OK/Yes/Open buttons, permission grants, or uninstall dialogs.
        private val confirmLabels = setOf("install", "update", "установить", "обновить", "安装", "更新")

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, UpdateAccessibilityService::class.java)
            val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return services.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
