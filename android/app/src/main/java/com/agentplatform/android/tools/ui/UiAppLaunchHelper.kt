package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent

internal data class UiLaunchTarget(
    val intent: Intent,
    val activityName: String?
)

internal object UiAppLaunchHelper {
    fun launchTarget(context: Context, packageName: String): UiLaunchTarget? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return intent?.let { UiLaunchTarget(it, it.component?.className) }
    }

    fun knownLaunchTarget(packageName: String): UiLaunchTarget? =
        when (packageName) {
            "com.tencent.mm" -> launcherTarget(packageName, "com.tencent.mm.ui.LauncherUI")
            "com.tencent.mobileqq" -> launcherTarget(packageName, "com.tencent.mobileqq.activity.SplashActivity")
            "com.max.xiaoheihe" -> launcherTarget(packageName, "com.max.xiaoheihe.SplashActivity")
            else -> null
        }

    private fun launcherTarget(packageName: String, activityName: String): UiLaunchTarget =
        UiLaunchTarget(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(packageName, activityName),
            activityName
        )
}
