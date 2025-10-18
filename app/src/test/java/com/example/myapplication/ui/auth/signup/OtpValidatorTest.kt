package com.example.myapplication.ui.auth.signup

import org.junit.Assert.assertEquals
import org.junit.Test

class OtpValidatorTest {

    @Test
    fun `empty otp returns EMPTY`() {
        assertEquals(OtpValidator.Result.EMPTY, OtpValidator.validate(""))
    }

    @Test
    fun `short otp returns LENGTH`() {
        assertEquals(OtpValidator.Result.LENGTH, OtpValidator.validate("123"))
    }

    @Test
    fun `non numeric otp returns FORMAT`() {
        assertEquals(OtpValidator.Result.FORMAT, OtpValidator.validate("12ab56"))
    }

    @Test
    fun `valid otp returns NONE`() {
        assertEquals(OtpValidator.Result.NONE, OtpValidator.validate("123456"))
    }
}

