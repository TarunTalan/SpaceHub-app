package com.example.myapplication.ui.common

import android.os.CountDownTimer
import java.util.Locale

/**
 * Helper to manage OTP resend cooldown and timer UI updates.
 * Call start(remainingMillis) to begin cooldown, cancel() to stop.
 * The helper drives the resend control enabled state, alpha, timer visibility and timer text.
 */
class OtpResendCooldownHelper(
    private val cooldownMillis: Long = 30_000L,
    private val setResendEnabled: (Boolean) -> Unit,
    private val setResendAlpha: (Float) -> Unit,
    private val setTimerVisible: (Boolean) -> Unit,
    private val setTimerText: (String) -> Unit,
    private val onFinish: (() -> Unit)? = null
) {
    private var timer: CountDownTimer? = null

    fun start(remainingMillis: Long = cooldownMillis) {
        timer?.cancel()
        if (remainingMillis <= 0L) {
            setTimerVisible(false)
            setResendEnabled(true)
            setResendAlpha(1.0f)
            onFinish?.invoke()
            return
        }

        setResendEnabled(false)
        setResendAlpha(0.5f)
        setTimerVisible(true)

        timer = object : CountDownTimer(remainingMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val min = seconds / 60
                val sec = seconds % 60
                setTimerText(String.format(Locale.getDefault(), "%d:%02d", min, sec))
            }

            override fun onFinish() {
                setTimerText("00:00")
                setTimerVisible(false)
                setResendEnabled(true)
                setResendAlpha(1.0f)
                onFinish?.invoke()
            }
        }

        timer?.start()
    }

    fun startWithEndMillis(endMillis: Long) {
        val remaining = endMillis - System.currentTimeMillis()
        start(remaining)
    }

    fun cancel() {
        timer?.cancel()
        timer = null
    }
}

/**
 * Create a standardized OtpResendCooldownHelper wired to the provided setters.
 * Use this from verification fragments to avoid duplicating the constructor logic.
 */
fun createOtpCooldownHelper(
    resendCooldownMillis: Long,
    setResendEnabled: (Boolean) -> Unit,
    setResendAlpha: (Float) -> Unit,
    setTimerVisible: (Boolean) -> Unit,
    setTimerText: (String) -> Unit,
    onFinish: (() -> Unit)? = null
): OtpResendCooldownHelper = OtpResendCooldownHelper(
    cooldownMillis = resendCooldownMillis,
    setResendEnabled = setResendEnabled,
    setResendAlpha = setResendAlpha,
    setTimerVisible = setTimerVisible,
    setTimerText = setTimerText,
    onFinish = onFinish
)

/**
 * Start the cooldown if a temporary token is present in nav args or in the ViewModel.
 */
fun startCooldownIfTokenPresent(
    argToken: String?,
    vmToken: String?,
    getVmCooldownEndMillis: () -> Long?,
    setVmCooldownEndMillis: (Long) -> Unit,
    cooldownHelper: OtpResendCooldownHelper?,
    cooldownMillis: Long
) {
    if (!argToken.isNullOrBlank() || !vmToken.isNullOrBlank()) {
        if (getVmCooldownEndMillis() == null) {
            val endMillis = System.currentTimeMillis() + cooldownMillis
            setVmCooldownEndMillis(endMillis)
            cooldownHelper?.start(cooldownMillis)
        }
    }
}

/**
 * Resume an active cooldown from ViewModel's stored endMillis. If no cooldown is active, no-op.
 * If the stored endMillis has already passed, clear it.
 */
fun resumeCooldownFromVm(
    getVmCooldownEndMillis: () -> Long?,
    clearVmCooldown: () -> Unit,
    cooldownHelper: OtpResendCooldownHelper?
) {
    val endMillis = getVmCooldownEndMillis() ?: return
    val remaining = endMillis - System.currentTimeMillis()
    if (remaining > 0L) {
        cooldownHelper?.startWithEndMillis(endMillis)
    } else {
        clearVmCooldown()
    }
}
