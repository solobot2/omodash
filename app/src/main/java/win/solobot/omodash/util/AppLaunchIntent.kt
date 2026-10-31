package win.solobot.omodash.util

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import win.solobot.omodash.data.VehicleAppBinding

object AppLaunchIntent {
    fun resolve(context: Context, packageName: String): Intent? {
        if (packageName.isBlank()) return null
        val binding = win.solobot.omodash.data.VehiclePresets.current().apps.values
            .firstOrNull { it.packageName == packageName } ?: VehicleAppBinding(packageName)
        return resolveBinding(context, binding)
    }

    fun resolveBinding(context: Context, binding: VehicleAppBinding): Intent? {
        val pm = context.packageManager
        val candidates = buildList {
            for (pkg in listOf(binding.packageName) + binding.fallbackPackages) {
                if (pkg.isBlank()) continue
                binding.activityName?.let { activity ->
                    add(Intent(binding.intentAction ?: Intent.ACTION_MAIN).setClassName(pkg, activity))
                }
                binding.intentAction?.let { action -> add(Intent(action).setPackage(pkg).apply {
                    binding.intentCategory?.let(::addCategory)
                }) }
                pm.getLaunchIntentForPackage(pkg)?.let(::add)
                if (binding.activityName == null) pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)
                    .firstOrNull()?.activityInfo?.let { add(Intent(Intent.ACTION_MAIN).setClassName(it.packageName, it.name)) }
            }
            if (binding.packageName.isBlank() && binding.intentAction != null) add(Intent(binding.intentAction).apply {
                binding.intentCategory?.let(::addCategory)
            })
        }
        // Intent.resolveActivity() returns an explicit ComponentName even when the APK
        // is absent. Ask PackageManager so disabled controls reflect real availability.
        val resolved = candidates.firstNotNullOfOrNull { candidate ->
            pm.resolveActivity(candidate, 0)?.activityInfo?.takeIf {
                it.enabled && it.applicationInfo.enabled && it.exported
            }?.let { candidate to it }
        } ?: return null
        val (intent, activity) = resolved
        binding.intExtras.forEach { (key, value) -> intent.putExtra(key, value) }
        intent.component = ComponentName(activity.packageName, activity.name)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
