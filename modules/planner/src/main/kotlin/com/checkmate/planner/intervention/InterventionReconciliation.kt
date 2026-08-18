package com.checkmate.planner.intervention

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Proactive Execution Engine — Step 7 (Blueprint Part One, §4).
 *
 * "Process dies -> Room remains -> Worker/receiver restarts -> unfinished transaction
 * discovered -> FSM reconciles it." This is that sweep. Call from both
 * CheckmateApp.onCreate() and BootReceiver — mirrors this codebase's existing
 * WorkModeManager.init() pattern of running the same reconciliation logic from both entry
 * points, since either can be the first code to run after a restart. Fire-and-forget by
 * design (like CheckmateApp's existing `Thread { ... }.start()` calls for guardian
 * alerts) — nothing in either caller's synchronous startup path should block on this.
 */
object InterventionReconciliation {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun runAtStartup(context: Context) {
        scope.launch {
            val dao = InterventionDatabase.getInstance(context).interventionTransactionDao()
            TaskEscrow(dao).reconcileUnfinished()
        }
    }
}
