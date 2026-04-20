package com.checkmate.callhandler

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * During STUDY mode: silences/rejects calls with a notification.
 * In other modes: allows calls through normally.
 */
class CheckmateCallScreeningService : CallScreeningService() {

    private const val TAG = "CheckmateCallScreening"

    override fun onScreenCall(callDetails: Call.Details) {
        val response = CallResponse.Builder()

        if (ModeGuard.isStudyMode()) {
            Log.d(TAG, "Incoming call silenced — Study mode active")
            response
                .setDisallowCall(false)     // allow call, but silence
                .setSilenceCall(true)        // vibrate only / silent
                .setSkipCallLog(false)
                .setSkipNotification(false)
        } else {
            Log.d(TAG, "Call allowed — not in study mode")
            response
                .setDisallowCall(false)
                .setSilenceCall(false)
        }

        respondToCall(callDetails, response.build())
    }
}
