package com.checkmate.core.stopwatch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object NotificationPermissionHelper {
    const val REQUEST_CODE_POST_NOTIFICATIONS = 1003

    fun shouldRequestPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isPermissionGranted(context)

    fun isPermissionGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

    fun requestPermission(activity: Activity, requestCode: Int = REQUEST_CODE_POST_NOTIFICATIONS) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
        }
    }
}
