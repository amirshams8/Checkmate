package com.checkmate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.checkmate.core.CheckmateState
import com.checkmate.core.stopwatch.NotificationPermissionHelper
import com.checkmate.ui.main.MainScreen
import com.checkmate.ui.theme.CheckmateTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY = 1001
        private const val REQUEST_CODE_MIC     = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CheckmateState.init(applicationContext)

        if (NotificationPermissionHelper.shouldRequestPermission(this)) {
            NotificationPermissionHelper.requestPermission(this)
        }
        requestOverlayPermissionIfNeeded()
        requestMicPermissionIfNeeded()

        setContent {
            CheckmateTheme {
                Surface {
                    MainScreen()
                }
            }
        }
    }

    private fun requestMicPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_MIC
            )
        }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission for attention timer", Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_CODE_OVERLAY
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NotificationPermissionHelper.REQUEST_CODE_POST_NOTIFICATIONS) {
            if (!NotificationPermissionHelper.isPermissionGranted(this)) {
                Toast.makeText(this, "Notification permission needed for reminders", Toast.LENGTH_LONG).show()
            }
        }
    }
}
