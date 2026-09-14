package com.checkmate.testresults

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.checkmate.ui.testresults.TestResultsScreen
import com.checkmate.ui.testresults.TestmateWebScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testing backlog item 1 (baseline smoke test) — Testmate half of "verify
 * Planner/Mentor/Testmate screens initialize". Uses createComposeRule() (a bare
 * Compose host, not a full MainActivity launch) to compose TestResultsScreen and
 * TestmateWebScreen directly with their default `vm = viewModel()`, the same
 * production composables MainScreen's NavHost instantiates for the "test_results"
 * and "test_web" routes. A rememberNavController() stand-in is enough since
 * neither screen is asserted against actually navigating anywhere here — the bar
 * for this test is composition completing without throwing, same "verify X
 * initializes" bar ScreenInitializationSmokeTest applies to the bottom-nav screens.
 *
 * TestmateWebScreen embeds a real android.webkit.WebView (see its own file) —
 * this only asserts it composes, not that any page actually loads, since that
 * depends on the Test Platform base URL/token configured in Settings, which is
 * out of scope for a smoke test (P4 Q-bank / provenance work covers that later).
 */
@RunWith(AndroidJUnit4::class)
class TestmateScreenSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun testResultsScreenInitializes() {
        composeRule.setContent {
            TestResultsScreen(navController = rememberNavController())
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test
    fun testmateWebScreenInitializes() {
        composeRule.setContent {
            TestmateWebScreen(navController = rememberNavController())
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}
