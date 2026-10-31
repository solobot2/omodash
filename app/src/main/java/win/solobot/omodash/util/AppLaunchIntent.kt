package win.solobot.omodash.util

import android.content.Context
import android.content.Intent

object AppLaunchIntent {
    fun resolve(context: Context, packageName: String): Intent? {
        if (packageName.isBlank()) return null
        val pm = context.packageManager
        val action = win.solobot.omodash.data.VehiclePresets.current().apps.values
            .firstOrNull { it.packageName == packageName }?.intentAction
        val vendor = action?.let { Intent(it).setPackage(packageName) }
        val intent = vendor?.takeIf { it.resolveActivity(pm) != null }
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(packageName), 0).firstOrNull()
                ?.activityInfo?.let { Intent(Intent.ACTION_MAIN).setClassName(it.packageName, it.name) }
            ?: return null
        intent.component = intent.resolveActivity(pm) ?: return null
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
