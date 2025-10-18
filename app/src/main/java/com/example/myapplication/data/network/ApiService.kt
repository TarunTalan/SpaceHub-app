package com.example.myapplication.data.network

import com.example.myapplication.data.auth.model.ForgotPasswordRequest
import com.example.myapplication.data.auth.model.ForgotPasswordResponce
import com.example.myapplication.data.auth.model.SignupRequest
import com.example.myapplication.data.auth.model.SignupResponse
import com.example.myapplication.data.auth.model.LoginRequest
import com.example.myapplication.data.auth.model.LoginResponse
import com.example.myapplication.data.auth.model.ResendForgotOtpRequest
import com.example.myapplication.data.auth.model.ResendForgotOtpResponse
import com.example.myapplication.data.auth.model.ResendSignupOtpRequest
import com.example.myapplication.data.auth.model.ResendSignupOtpResponse
import com.example.myapplication.data.auth.model.SigupOtpRequest
import com.example.myapplication.data.auth.model.SignupOtpResponse
import com.example.myapplication.data.auth.model.ValidateForgotOtpRequest
import com.example.myapplication.data.auth.model.ValidateForgotOtpResponce
import com.example.myapplication.data.auth.model.ResetPasswordRequest
import com.example.myapplication.data.auth.model.ResetPasswordResponce
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST


interface ApiService {
    @POST("registration")
    suspend fun signup(@Body body: SignupRequest): Response<SignupResponse>

    // Send or verify OTP for registration via common request model
    @POST("validateregisterotp")
    suspend fun sendSignupOtp(@Body body: SigupOtpRequest): Response<SignupOtpResponse>

    // Login with email/password; server returns tokens under data
    @POST("login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("forgotpassword")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): Response<ForgotPasswordResponce>

    @POST("validateforgototp")
    suspend fun validateForgotOtp(@Body body: ValidateForgotOtpRequest): Response<ValidateForgotOtpResponce>

    // Reset password after OTP verification
    @POST("resetpassword")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): Response<ResetPasswordResponce>

    @POST("resendforgototp")
    suspend fun resendForgotOtp(@Body body: ResendForgotOtpRequest): Response<ResendForgotOtpResponse>

    @POST("resendotp")
    suspend fun resendSignupOtp(@Body body: ResendSignupOtpRequest): Response<ResendSignupOtpResponse>
}
