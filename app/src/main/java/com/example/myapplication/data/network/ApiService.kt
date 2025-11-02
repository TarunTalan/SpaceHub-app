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
import com.example.myapplication.data.dashboard.model.CreateCommunityResponse
import com.example.myapplication.data.dashboard.model.GetProfileResponse
import com.example.myapplication.data.dashboard.model.UpdateProfilePicResponse
import com.example.myapplication.data.dashboard.model.UpdateProfileRequest
import com.example.myapplication.data.dashboard.model.UpdateProfileResponse
import com.example.myapplication.data.dashboard.model.UploadProfileResponse
import com.example.myapplication.data.dashboard.model.UsernameRequest
import com.example.myapplication.data.dashboard.model.UsernameResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Query

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

    // Upload profile: sends email and image information
    // with email as first field and image uri as second.
    // Also include an optional file part named "image" for servers that accept an actual file upload.
    @Multipart
    @POST("dashboard/upload-profile-image")
    suspend fun uploadProfile(
        @Part("email") email: RequestBody,
        @Part("image_uri") imageUri: RequestBody,
        @Part image: MultipartBody.Part? = null
    ): Response<UploadProfileResponse>

    // Validate username: server returns status and data (the accepted username) on success
    @POST("dashboard/set-username")
    suspend fun validateUsername(@Body body: UsernameRequest): Response<UsernameResponse>

    // Flexible createCommunity: accept both email key variations and image_uri text + image file part
    @Multipart
    @POST("community/create")
    suspend fun createCommunity(
        @Part("name") name: RequestBody,
        @Part("description") description: RequestBody,
        @Part("createdByEmail") createdByEmail: RequestBody? = null,
        @Part("image_uri") imageUri: RequestBody? = null,
        @Part imageFile: MultipartBody.Part? = null
    ): Response<CreateCommunityResponse>

    // include email as query parameter
    @PUT("profile/updateProfile")
    suspend fun updateProfile(
        @Query("email") email: String,
        @Body body: UpdateProfileRequest
    ): Response<UpdateProfileResponse>

    // send email as query parameter for avatar upload
    @Multipart
    @POST("profile/avatar")
    suspend fun updateProfilePic(
        @Query("email") email: String,
        @Part file: MultipartBody.Part? = null
    ): Response<UpdateProfilePicResponse>

    // Get user profile data
    @retrofit2.http.GET("profile/getProfile")
    suspend fun getProfile(
        @Query("email") email: String
    ): Response<GetProfileResponse>
}
