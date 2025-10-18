package com.example.myapplication.ui.auth.onboarding

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R

/**
 * Onboarding screen that displays the app logo with orbital rings
 * and animates content reveal after a brief delay.
 */
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

    /**
     * Hides the provided views initially.
     */
    private fun hideViews(vararg views: View) {
        views.forEach { it.visibility = View.INVISIBLE }
    }

    /**
     * Animates the logo and content upward by a percentage of screen height.
     * Orbits remain fixed at their original position (45% from top).
     */
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

            // Animate logo upward
            logo.animate()
                .translationY(-moveUpDistance)
                .setDuration(ANIMATION_DURATION_MS)
                .setInterpolator(interpolator)
                .start()

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

    /**
     * Animates a view to fade in and move upward.
     */
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

            animate()
                .alpha(1f)
                .translationY(-moveUpDistance)
                .setDuration(ANIMATION_DURATION_MS)
                .setStartDelay(startDelay)
                .setInterpolator(interpolator)
                .start()
        }
    }

    /**
     * Shows views immediately without animation (fallback).
     */
    private fun showViewsImmediately(vararg views: View) {
        views.forEach { it.visibility = View.VISIBLE }
    }

    /**
     * Navigates to the login screen.
     */
    private fun navigateToLogin() {
        findNavController().navigate(R.id.action_onboardingFragment_to_loginFragment)
    }

    /**
     * Navigates to the signup screen.
     */
    private fun navigateToSignup() {
        findNavController().navigate(R.id.action_onboardingFragment_to_signupFragment)
    }
}
