package com.checkmate

import android.Manifest
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testing backlog item 1 (baseline smoke test) — "install APK / launch / verify no
 * startup crash". This launches the real MainActivity (which chains into
 * CheckmateApp.onCreate() first — see its own doc: CheckmatePrefs, CheckmateState,
 * BehaviorLedger.init(), WorkModeManager.init(), all the scheduler wiring, etc all
 * run before this test's rule finishes launching) and asserts the process is still
 * alive and RESUMED afterward.
 *
 * Two runtime permissions the app requests on first launch (see MainActivity's
 * requestMicPermissionIfNeeded / NotificationPermissionHelper) are granted up
 * front via GrantPermissionRule so their system dialogs never appear and block
 * the test. RECORD_AUDIO and POST_NOTIFICATIONS are both grantable this way.
 *
 * The overlay permission (requestOverlayPermissionIfNeeded → ACTION_MANAGE_OVERLAY_PERMISSION)
 * is NOT grantable via GrantPermissionRule — SYSTEM_ALERT_WINDOW is an AppOps special
 * permission, not a normal runtime one. Without it, MainActivity launches
 * ACTION_MANAGE_OVERLAY_PERMISSION as a side-effect Activity, which would otherwise
 * leave this test's Activity in a PAUSED, not RESUMED, state. connectedCheck must grant
 * it beforehand at the CI/device level:
 *   adb shell appops set com.checkmate SYSTEM_ALERT_WINDOW allow
 * (grayed out here since it's a device/CI setup step, not test code — see the
 * gradlew connectedCheck step already owned outside this file).
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesWithoutCrashAndReachesResumed() {
        composeRule.waitForIdle()
        val state = composeRule.activity.lifecycle.currentState
        org.junit.Assert.assertTrue(
            "Expected MainActivity to reach at least STARTED after launch, was $state",
            state.isAtLeast(Lifecycle.State.STARTED)
        )
    }
}
