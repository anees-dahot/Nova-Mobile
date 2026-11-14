package com.anees.novamobile.transfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Wi-Fi Direct needs "Nearby devices" (Android 13+) or precise location (Android 10–12). */
object BeamPermissions {
    /** What Wi-Fi Direct itself checks. */
    private fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** What to ask for. Android 12 ignores a request for precise location on its own: approximate has to come with it. */
    fun needed(): Array<String> = when {
        Build.VERSION.SDK_INT in 31..32 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> required()
    }

    fun granted(ctx: Context) = required().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Asks if needed, then runs [then] either way (without the permission only same-Wi-Fi transfers work). */
    fun ask(activity: ComponentActivity, then: (granted: Boolean) -> Unit) {
        if (granted(activity) || Build.VERSION.SDK_INT < 29) { then(granted(activity)); return }
        var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
        launcher = activity.activityResultRegistry.register("beam_perm_${System.nanoTime()}", ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            launcher?.unregister()
            then(granted(activity))   // "approximate only" (Android 12) isn't enough for Wi-Fi Direct
        }
        launcher.launch(needed())
    }
}
