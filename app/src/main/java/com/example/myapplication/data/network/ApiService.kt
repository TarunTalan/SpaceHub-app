package com.example.myapplication.data.network

import com.example.myapplication.data.auth.model.SignupRequest
import com.example.myapplication.data.auth.model.SignupResponse
import com.example.myapplication.data.auth.model.LoginRequest
import com.example.myapplication.data.auth.model.LoginResponse
import com.example.myapplication.data.auth.model.OtpRequest
import com.example.myapplication.data.auth.model.SignupOtpResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST


interface ApiService {
    @POST("registration")
    suspend fun signup(@Body body: SignupRequest): Response<SignupResponse>

    // Send or verify OTP for registration via common request model
    @POST("validateregisterotp")
    suspend fun sendSignupOtp(@Body body: OtpRequest): Response<SignupOtpResponse>

    // Login with email/password; server returns tokens under data
    @POST("login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    // ...existing code (other endpoints if any)...
}
