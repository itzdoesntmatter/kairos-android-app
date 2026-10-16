package digital.tonima.kairos.ui.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import digital.tonima.kairos.core.R

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}

fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.startActivity(Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM"))
    } else {
        Toast.makeText(context, R.string.not_applicable_on_this_android_version, Toast.LENGTH_SHORT).show()
    }
}

fun openFullScreenIntentSettings(context: Context) {
    val intent =
        Intent("android.settings.MANAGE_APP_ALL_ALARMS").apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    val resolveInfo = context.packageManager.resolveActivity(intent, 0)
    if (resolveInfo != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, R.string.not_applicable_on_this_android_version, Toast.LENGTH_SHORT).show()
    }
}
