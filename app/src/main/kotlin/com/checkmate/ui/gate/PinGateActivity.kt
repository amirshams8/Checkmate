package com.checkmate.ui.gate

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.checkmate.workmode.UninstallGuard
import com.checkmate.workmode.UninstallGuard.UnlockResult

/**
 * PinGateActivity — the PIN prompt ForegroundPinGate puts on top of the battery / Settings
 * screens. Plain views on purpose: no Compose/theme dependency, so it can come up fast from a
 * background service. A correct guardian PIN opens UninstallGuard's normal unlock window and
 * finishes (the gated screen reappears underneath); back / "Go back" bounces Home.
 */
class PinGateActivity : Activity() {

    private lateinit var pinField: EditText
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF101114"))
            setPadding(pad, pad, pad, pad)
        }

        val blockedApp = intent?.getStringExtra("reason") == "blocked_app"
        root.addView(TextView(this).apply {
            text = if (blockedApp) "App locked" else "Guardian PIN required"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = if (blockedApp) "This app is blocked right now. Guardian PIN required to open it."
                   else "This screen can stop or disable Checkmate."
            textSize = 14f
            setTextColor(Color.parseColor("#FFB0B3BA"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 3, 0, pad)
        })

        pinField = EditText(this).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#FF6B6F78"))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
        }
        root.addView(pinField)

        message = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FFFF6B6B"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(message)

        root.addView(Button(this).apply {
            text = "Unlock"
            setOnClickListener { submit() }
        })
        root.addView(Button(this).apply {
            text = "Go back"
            setOnClickListener { goHome() }
        })

        setContentView(root)
        showLockoutIfAny()
    }

    private fun showLockoutIfAny() {
        if (UninstallGuard.isLockedOut()) {
            message.text = "Too many wrong PINs. Try again in ${UninstallGuard.lockoutRemainingSeconds()}s."
        }
    }

    private fun submit() {
        val pin = pinField.text.toString().trim()
        if (pin.isEmpty()) return
        when (val r = UninstallGuard.unlockWithPin(pin)) {
            is UnlockResult.Success -> finish()
            is UnlockResult.WrongPin -> {
                message.text = "Wrong PIN."
                pinField.setText("")
            }
            is UnlockResult.NoPinConfigured -> goHome()
            is UnlockResult.LockedOut -> {
                message.text = "Too many wrong PINs. Try again in ${r.secondsLeft}s."
                pinField.setText("")
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    @Deprecated("Back must not reveal the gated screen")
    override fun onBackPressed() {
        goHome()
    }
}
