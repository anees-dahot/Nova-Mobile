package com.anees.novamobile.transfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Wi-Fi Direct needs "Nearby devices" (Android 13+) or location (Android 10–12). */
object BeamPermissions {
    fun needed(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun granted(ctx: Context) = needed().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Asks if needed, then runs [then] either way (without the permission only same-Wi-Fi transfers work). */
    fun ask(activity: ComponentActivity, then: (granted: Boolean) -> Unit) {
        if (granted(activity) || Build.VERSION.SDK_INT < 29) { then(granted(activity)); return }
        var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
        launcher = activity.activityResultRegistry.register("beam_perm_${System.nanoTime()}", ActivityResultContracts.RequestMultiplePermissions()) { res ->
            launcher?.unregister()
            then(res.values.all { it })
        }
        launcher.launch(needed())
    }
}
