package io.github.bossmanct.aspectly.adb

/**
 * Every shell command Aspectly is capable of running. There is no other path to the
 * ADB connection — no string passthrough, no user-supplied arguments, nothing exported
 * that reaches here. Aspectly holds shell-level access to the device; a generic "run
 * this" entry point would turn any bug in this app into a device-wide privilege
 * escalation.
 *
 * Package names are validated against [PACKAGE_NAME] and must match an app that is
 * actually installed before any command is built.
 */
object AspectlyCommands {

    private val PACKAGE_NAME =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /**
     * Gates that make an app eligible for letterboxing, plus the flag that supplies the
     * 16:9 value. Verified on a Galaxy Z Fold 8 (SM-F971U, Android 17).
     *
     * FORCE_NON_RESIZE_APP is deliberately absent and must never be added. It is not
     * required — letterboxing is identical without it — and on real hardware it makes
     * the target app close outright when the device is folded or unfolded, because a
     * non-resizable task cannot be re-hosted across the Fold's two physical displays.
     */
    private const val UNIVERSAL_RESIZABLE = "UNIVERSAL_RESIZABLE_BY_DEFAULT"
    private const val PORTRAIT_ONLY = "OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY"
    private const val MIN_ASPECT_RATIO = "OVERRIDE_MIN_ASPECT_RATIO"
    private const val MIN_ASPECT_RATIO_LARGE = "OVERRIDE_MIN_ASPECT_RATIO_LARGE"

    /**
     * Samsung repopulates the MIN_ASPECT_RATIO_LARGE override map at boot, discarding
     * user entries, so this must be reapplied after every restart. The other three
     * flags survive but do nothing on their own — which is why a reboot reliably
     * returns the device to stock.
     */
    fun applyAspectRatio(packageName: String): List<String> {
        val pkg = validated(packageName)
        return listOf(
            "am compat disable $UNIVERSAL_RESIZABLE $pkg",
            "am compat disable $PORTRAIT_ONLY $pkg",
            "am compat enable $MIN_ASPECT_RATIO $pkg",
            "am compat enable $MIN_ASPECT_RATIO_LARGE $pkg",
            "am force-stop $pkg",
        )
    }

    /** Clears every override Aspectly set for this app, returning it to stock. */
    fun clearAspectRatio(packageName: String): List<String> {
        val pkg = validated(packageName)
        return listOf(
            "am compat reset-all $pkg",
            "am force-stop $pkg",
        )
    }

    /**
     * Letterbox position is global, not per-app — the API takes no package argument.
     * Unlike the compat overrides, this resets itself on reboot.
     */
    fun setBarPosition(side: BarSide): String =
        "wm set-letterbox-style " +
            "--isHorizontalReachabilityEnabled false " +
            "--horizontalPositionMultiplier ${side.multiplier} " +
            "--cornerRadius 0"

    fun clearBarPosition(): String = "wm reset-letterbox-style"

    /**
     * Grants Aspectly permission to turn wireless debugging back on by itself.
     *
     * Opt-in, and the only command here that changes what Aspectly can do rather than
     * how an app is displayed. Without it the app cannot recover after a restart,
     * because Samsung's Auto Blocker disables the debugging toggles at boot and
     * re-enabling them requires the access they grant.
     *
     * Narrower than the ADB key already held — shell access is strictly more powerful.
     * It is used because it survives a reboot.
     */
    fun grantSelfSecureSettings(packageName: String): String {
        val pkg = validated(packageName)
        return "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
    }

    /** Revokes the above. Paired with it so the grant is never one-way. */
    fun revokeSelfSecureSettings(packageName: String): String {
        val pkg = validated(packageName)
        return "pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS"
    }

    /** Reads which overrides are currently live, so the UI can reconcile rather than assume. */
    fun readCompatState(): String = "dumpsys platform_compat"

    fun readLetterboxState(): String = "wm get-letterbox-style"

    private fun validated(packageName: String): String {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
        return packageName
    }
}

/**
 * Which side the black bar sits on. The app is pushed to the opposite edge, so the
 * dead space lands where reach is worst — bar on the left puts content under a right
 * thumb.
 */
enum class BarSide(val multiplier: String, val description: String) {
    LEFT("1.0", "black bar on the left"),
    CENTER("0.5", "black bars on both sides"),
    RIGHT("0.0", "black bar on the right"),
}
