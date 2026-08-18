package com.checkmate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.checkmate.core.CheckmateState
import com.checkmate.core.stopwatch.NotificationPermissionHelper
import com.checkmate.service.InterventionNotifier
import com.checkmate.ui.home.HomeViewModel
import com.checkmate.ui.main.MainScreen
import com.checkmate.ui.main.PendingNegotiation
import com.checkmate.ui.theme.CheckmateTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY = 1001
        private const val REQUEST_CODE_MIC     = 1002
    }

    private lateinit var homeViewModel: HomeViewModel

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Pass the raw resultCode + data directly to the ViewModel.
            // The ViewModel will forward them into AttentionCycleService's intent,
            // so getMediaProjection() is called only AFTER startForeground() runs.
            homeViewModel.onProjectionGranted(
                applicationContext,
                result.resultCode,
                result.data
            )
        } else {
            homeViewModel.onProjectionDenied(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CheckmateState.init(applicationContext)

        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]

        lifecycleScope.launch {
            homeViewModel.requestProjection.collect {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }

        if (NotificationPermissionHelper.shouldRequestPermission(this)) {
            NotificationPermissionHelper.requestPermission(this)
        }
        requestOverlayPermissionIfNeeded()
        requestMicPermissionIfNeeded()

        // Proactive Execution Engine — Step 10 (Blueprint §16 "Talk to Checkmate"). Read the
        // three extras InterventionNotifier's talkPendingIntent() already attaches (has done
        // so since Step 9 — see that method's own doc comment on why nothing consumed them
        // until now). removeExtra() immediately after reading so a later onCreate() from an
        // Activity recreation (e.g. rotation) with the same Intent doesn't re-trigger
        // navigation into the negotiation screen a second time.
        val pendingNegotiation = intent.getStringExtra(InterventionNotifier.EXTRA_TRANSACTION_ID)
            ?.let { transactionId ->
                val taskId = intent.getStringExtra(InterventionNotifier.EXTRA_TASK_ID)
                val lateMinutes = intent.getIntExtra(InterventionNotifier.EXTRA_LATE_MINUTES, 0)
                taskId?.let { PendingNegotiation(transactionId, it, lateMinutes) }
            }
        intent.removeExtra(InterventionNotifier.EXTRA_TRANSACTION_ID)
        intent.removeExtra(InterventionNotifier.EXTRA_TASK_ID)
        intent.removeExtra(InterventionNotifier.EXTRA_LATE_MINUTES)

        setContent {
            CheckmateTheme {
                Surface {
                    MainScreen(homeViewModel = homeViewModel, pendingNegotiation = pendingNegotiation)
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
