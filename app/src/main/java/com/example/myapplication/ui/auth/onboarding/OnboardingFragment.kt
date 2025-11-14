package com.example.myapplication.ui.auth.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R


@SuppressLint("SourceLockedOrientationActivity")
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    private companion object {
        const val ANIMATION_DELAY_MS = 1400L
        const val ANIMATION_DURATION_MS = 600L
        const val VERTICAL_MOVE_PERCENTAGE = 0.15f

        // Staggered reveal delays
        const val TITLE_DELAY_MS = 150L
        const val SUBTITLE_DELAY_MS = 230L
        const val LOGIN_BTN_DELAY_MS = 310L
        const val SIGNUP_BTN_DELAY_MS = 390L
    }

    private var previousOrientation: Int? = null

    // If onboarding receives an email (deep link / arg), persist and forward it to signup
    private var incomingEmail: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Lock orientation to portrait while onboarding is visible
        try {
            previousOrientation = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } catch (_: Exception) {
            // ignore if activity is null or setting orientation fails
        }

        val imgLogo = view.findViewById<ImageView>(R.id.img_logo)
        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val tvSubtitle = view.findViewById<TextView>(R.id.tv_subtitle)
        val btnLogin = view.findViewById<Button>(R.id.btn_login)
        val btnSignUp = view.findViewById<Button>(R.id.btn_sign_up)

        // Hide content initially - will be revealed with animation
        hideViews(tvTitle, tvSubtitle, btnLogin, btnSignUp)

        // If an email was passed to this onboarding fragment (e.g., deep link), persist it and keep for forwarding
        try {
            incomingEmail = arguments?.getString("email")?.takeIf { it.isNotBlank() }
            incomingEmail?.let { email ->
                requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit { putString("email", email) }
            }
        } catch (_: Exception) {
        }

        // Setup navigation
        btnLogin.setOnClickListener { navigateToLogin() }
        btnSignUp.setOnClickListener { navigateToSignup() }

        // Start animation sequence after delay
        view.postDelayed({
            animateOnboarding(view, imgLogo, tvTitle, tvSubtitle, btnLogin, btnSignUp)
        }, ANIMATION_DELAY_MS)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Restore previous orientation when leaving onboarding
        try {
            previousOrientation?.let { activity?.requestedOrientation = it }
        } catch (_: Exception) {
            // ignore
        }
    }


    private fun hideViews(vararg views: View) {
        views.forEach { it.visibility = View.INVISIBLE }
    }


    private fun animateOnboarding(
        rootView: View,
        logo: ImageView,
        title: TextView,
        subtitle: TextView,
        loginBtn: Button,
        signupBtn: Button
    ) {
        try {
            val moveUpDistance = rootView.height * VERTICAL_MOVE_PERCENTAGE
            val interpolator = AccelerateDecelerateInterpolator()

            // Animate logo upward (guarded)
            try {
                logo.animate()
                    .translationY(-moveUpDistance)
                    .setDuration(ANIMATION_DURATION_MS)
                    .setInterpolator(interpolator)
                    .start()
            } catch (_: Exception) {
                try { logo.translationY = -moveUpDistance } catch (_: Exception) {}
            }

            // Animate and reveal content with staggered timing
            animateViewReveal(title, moveUpDistance, TITLE_DELAY_MS, interpolator)
            animateViewReveal(subtitle, moveUpDistance, SUBTITLE_DELAY_MS, interpolator)
            animateViewReveal(loginBtn, moveUpDistance, LOGIN_BTN_DELAY_MS, interpolator)
            animateViewReveal(signupBtn, moveUpDistance, SIGNUP_BTN_DELAY_MS, interpolator)

        } catch (_: Exception) {
            // Fallback: show content without animation if animation fails
            showViewsImmediately(title, subtitle, loginBtn, signupBtn)
        }
    }


    private fun animateViewReveal(
        view: View,
        moveUpDistance: Float,
        startDelay: Long,
        interpolator: AccelerateDecelerateInterpolator
    ) {
        view.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = 0f

            try {
                animate()
                    .alpha(1f)
                    .translationY(-moveUpDistance)
                    .setDuration(ANIMATION_DURATION_MS)
                    .setStartDelay(startDelay)
                    .setInterpolator(interpolator)
                    .start()
            } catch (_: Exception) {
                // Fallback to immediate reveal
                try { this.alpha = 1f; this.translationY = -moveUpDistance; this.visibility = View.VISIBLE } catch (_: Exception) {}
            }
        }
    }


    private fun showViewsImmediately(vararg views: View) {
        views.forEach { it.visibility = View.VISIBLE }
    }


    private fun navigateToLogin() {
        findNavController().navigate(R.id.action_onboardingFragment_to_loginFragment)
    }


    private fun navigateToSignup() {
        try {
            val bundle = incomingEmail?.let { bundleOf("email" to it) }
            findNavController().navigate(R.id.action_onboardingFragment_to_signupFragment, bundle)
        } catch (_: Exception) {
            findNavController().navigate(R.id.action_onboardingFragment_to_signupFragment)
        }
    }
}
