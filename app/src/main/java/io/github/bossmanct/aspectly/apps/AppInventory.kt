package io.github.bossmanct.aspectly.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Why an app can or cannot be controlled.
 *
 * This is surfaced to the user verbatim rather than hidden behind a disabled switch.
 * When Aspectly stops being able to help, it should say so and say why.
 */
sealed interface Support {
    data object Supported : Support

    /**
     * `DISABLE_OPT_OUT_UNIVERSAL_RESIZABLE_BY_DEFAULT` (change id 447301631) fires at
     * `targetSdk` 37 and — unlike every neighbouring compat change — is not marked
     * overridable. Once an app targets Android 17 its shape cannot be changed by
     * anything, including this app. There is no workaround to find later.
     */
    data class TargetsTooHigh(val targetSdk: Int) : Support

    /** Aspectly configuring Aspectly is not useful. */
    data object Self : Support
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val targetSdk: Int,
    val support: Support,
) {
    val controllable: Boolean get() = support is Support.Supported
}

object AppInventory {

    /** The version at which the non-overridable opt-out kill switch turns on. */
    const val LOCKED_FROM_TARGET_SDK = 37

    suspend fun installedApps(context: Context): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launchable = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.ResolveInfoFlags.of(0L),
            ).mapNotNull { it.activityInfo?.packageName }.toSet()

            launchable.mapNotNull { packageName ->
                runCatching {
                    val info = pm.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0L),
                    )
                    InstalledApp(
                        packageName = packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                        targetSdk = info.targetSdkVersion,
                        support = supportFor(context, info),
                    )
                }.getOrNull()
            }.sortedWith(
                compareByDescending<InstalledApp> { it.controllable }
                    .thenBy { it.label.lowercase() },
            )
        }

    private fun supportFor(context: Context, info: ApplicationInfo): Support = when {
        info.packageName == context.packageName -> Support.Self
        info.targetSdkVersion >= LOCKED_FROM_TARGET_SDK ->
            Support.TargetsTooHigh(info.targetSdkVersion)

        else -> Support.Supported
    }
}
