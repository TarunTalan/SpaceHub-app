package com.example.myapplication.data.network

import com.example.myapplication.data.auth.model.*
import com.example.myapplication.data.chat_room.model.CreateChatRoomResponse
import com.example.myapplication.data.chat_room.model.DeleteChatRoomRequest
import com.example.myapplication.data.chat_room.model.DeleteChatRoomResponse
import com.example.myapplication.data.chat_room.model.GetChatRoomSummaryResponse
import com.example.myapplication.data.community.model.*
import com.example.myapplication.data.dashboard.model.*
import com.example.myapplication.data.file_sharing.model.PresignedUrlRequest
import com.example.myapplication.data.file_sharing.model.PresignedUrlResponse
import com.example.myapplication.data.file_sharing.model.UploadFileResponse
import com.example.myapplication.data.friends.model.*
import com.example.myapplication.data.groups.model.CheckLocalGroupNameResponse
import com.example.myapplication.data.groups.model.CreateLocalGroupResponse
import com.example.myapplication.data.groups.model.DeleteLocalGroupRequest
import com.example.myapplication.data.groups.model.DeleteLocalGroupResponse
import com.example.myapplication.data.groups.model.GetAllLocalGroupsResponse
import com.example.myapplication.data.groups.model.GetLocalGroupDetailsResponse
import com.example.myapplication.data.groups.model.GroupGetAllMembersResponse
import com.example.myapplication.data.groups.model.JoinGroupByLinkRequest
import com.example.myapplication.data.groups.model.JoinGroupByLinkResponse
import com.example.myapplication.data.groups.model.UpdateLocalGroupProfileResponse
import com.example.myapplication.data.search.model.*
import com.example.myapplication.data.voice.model.CreateVoiceRoomResponse
import com.example.myapplication.data.voice.model.GetAllVoiceRoomsResponse
import com.example.myapplication.data.voice.model.JoinVoiceRoomResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*
import com.google.gson.JsonElement

interface ApiService {
    @POST("registration")
    suspend fun signup(@Body body: SignupRequest): Response<SignupResponse>

    // Send or verify OTP for registration via common request model
    @POST("validateregisterotp")
    suspend fun sendSignupOtp(@Body body: SigupOtpRequest, @Header("X-Skip-Auth") skipAuth: String? = null): Response<SignupOtpResponse>

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
    suspend fun resendSignupOtp(@Body body: ResendSignupOtpRequest ): Response<ResendSignupOtpResponse>

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

    // Get user profile data (enveloped response: { status, message, data })
    @GET("profile/getProfile")
    suspend fun getProfile(
        @Query("email") email: String
    ): Response<GetProfileEnvelope>

    // -------- Communities: Members / Roles / Remove / Leave / Update / Delete --------

    @POST("community/members")
    suspend fun getAllMembers(
        @Body body: GetAllMembersRequest
    ): Response<CommunityGetAllMembersResponse>

    @POST("community/changeRole")
    suspend fun changeRole(
        @Body body: ChangeRoleRequest
    ): Response<ChangeRoleResponse>

    @POST("community/removeMember")
    suspend fun removeMember(
        @Body body: RemoveMemberRequest
    ): Response<RemoveMemberResponse>

    // Leave a community
    @POST("community/leave")
    suspend fun leaveCommunity(
        @Body body: LeaveCommunityRequest
    ): Response<LeaveCommunityResponse>

    @Multipart
    @POST("community/{communityId}/upload-banner")
    suspend fun updateCommunityInfo(
        @Path("communityId") communityId: String,
        @Part("requesterEmail") requesterEmail: RequestBody,
        @Part("name") name: RequestBody,
        @Part("description") description: RequestBody,
        @Part imageFile: MultipartBody.Part? = null,
        @Part avatarFile: MultipartBody.Part? = null,
        @Part userAvatarFile: MultipartBody.Part? = null,
    ): Response<JsonElement>

    @Multipart
    @POST("community/{communityId}/upload-banner")
    suspend fun uploadCommunityBanner(
        @Path("communityId") communityId: String,
        @Query("requesterEmail") requesterEmail: String,
        @Part file: MultipartBody.Part? = null
    ): Response<UploadBannerResponse>

    @POST("community/delete")
    suspend fun deleteCommunity(
        @Body body: DeleteCommunityRequest
    ): Response<DeleteCommunityResponse>

    // Keep a single endpoint that returns communities where the requester is member or owner
    @GET("community/my-communities")
    suspend fun getMyCommunities(
        @Query("requesterEmail") requesterEmail: String
    ): Response<GetMyCommunitiesResponse>

    @POST("community/blockMember")
    suspend fun blockMember(
        @Body body: BlockMemberRequest
    ): Response<BlockMemberResponse>

    @POST("community/requestJoin")
    suspend fun requestToJoinCommunity(
        @Body body: RequestJoinRequest
    ): Response<RequestJoinResponse>

    // -------- Rooms (per provided API) --------

    @POST("community/{communityId}/rooms/create")
    suspend fun createRoom(
        @Path("communityId") communityId: String,
        @Body body: CreateRoomRequest
    ): Response<CreateRoomResponse>

    @DELETE("community/{communityId}/rooms/{roomId}")
    suspend fun deleteRoom(
        @Path("communityId") communityId: String,
        @Path("roomId") roomId: String,
        @Query("requesterEmail") email: String
    ): Response<DeleteRoomResponse>

    @GET("community/{communityId}/rooms/all")
    suspend fun getAllRooms(
        @Path("communityId") communityId: String
    ): Response<GetAllRoomsResponse>

    @PUT("community/{communityId}/rooms/{roomId}/rename")
    suspend fun renameRoom(
        @Path("communityId") communityId: String,
        @Path("roomId") roomId: String,
        @Body body: RenameRoomRequest
    ): Response<RenameRoomResponse>

    @GET("community/search")
    suspend fun searchCommunities(
        @Query("q") q: String,
        @Query("requesterEmail") requesterEmail: String,
        @Query("page") page: Int,
        @Query("size") size: Int,

    ): Response<SearchCommunitiesResponse>

    @GET("community/my-pending-requests")
    suspend fun getMyPendingRequestsRaw(
        @Query("requesterEmail") requesterEmail: String
    ): Response<JsonElement>

    @POST("community/rejectRequest")
    suspend fun rejectRequest(
        @Body body: RejectRequest
    ): Response<RejectRequestResponse>

    @POST("community/acceptRequest")
    suspend fun acceptRequest(
        @Body body: AcceptRequest
    ): Response<AcceptRequestResponse>

    @POST("community/invites/{communityId}/create")
    suspend fun createInviteLink(
        @Path("communityId") communityId: String,
        @Body body: CommunityInviteLinkRequest
    ): Response<CommunityInviteLinkResponse>

    @POST("community/invites/accept")
    suspend fun joinCommunityByLink(
        @Body body: JoinCommunityByLinkRequest
    ): Response<JoinCommunityByLinkResponse>

    @Multipart
    @POST("new-chatroom/create")
    suspend fun createChatRoom(
        @Part("name") name: RequestBody,
        @Part("roomCode") roomCode: RequestBody
    ): Response<CreateChatRoomResponse>

    @POST("friends/request")
    suspend fun sendFriendRequest(
        @Body body: SendFriendRequest
    ): Response<SendFriendRequestResponse>

    @POST("friends/respond")
    suspend fun respondFriendRequest(
        @Body body: RespondFriendRequest
    ): Response<RespondFriendRequestResponse>

    @POST("friends/list")
    suspend fun getFriendsList(
        @Body body: FriendsListRequest
    ): Response<FriendsListResponse>

    @POST("friends/pending/incoming")
    suspend fun getIncomingFriendRequests(
        @Body body: IncomingFriendRequest
    ): Response<IncomingFriendRequestResponse>

    @GET("search")
    suspend fun searchUsers(
        @Query("query") query: String,
        @Query("email") email: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<SearchUsersResponse>

    @Multipart
    @POST("local-group/create")
    suspend fun createLocalGroup(
        @Part("name") name: RequestBody,
        @Part("description") description: RequestBody,
        @Part("creatorEmail") creatorEmail: RequestBody,
        @Part imageFile: MultipartBody.Part? = null
    ): Response<CreateLocalGroupResponse>


    @GET("local-group/all")
    suspend fun getAllLocalGroups(
        @Query("requesterEmail") requesterEmail: String
    ): Response<GetAllLocalGroupsResponse>

    @POST("local-group/{groupId}/enter")
    suspend fun getLocalGroupDetails(
        @Path("groupId") groupId: String,
        @Query("requesterEmail") requesterEmail: String
    ): Response<GetLocalGroupDetailsResponse>

    @HTTP(method = "DELETE", path = "local-group/delete", hasBody = true)
    suspend fun deleteLocalGroup(
        @Body body: DeleteLocalGroupRequest
    ): Response<DeleteLocalGroupResponse>

    @GET("local-group/{localGroupId}/members")
    suspend fun getLocalGroupMembers(
        @Path("localGroupId") localGroupId: String,
        @Query("requesterEmail") requesterEmail: String
    ): Response<GroupGetAllMembersResponse>

    @Multipart
    @POST("local-group/{localGroupId}/settings")
    suspend fun updateLocalGroupSettings(
        @Path("localGroupId") localGroupId: String,
        @Part("requesterEmail") requesterEmail: RequestBody,
        @Part("name") name: RequestBody,
        @Part imageFile: MultipartBody.Part? = null
    ): Response<UpdateLocalGroupProfileResponse>

    @POST("local-group/join")
    suspend fun requestToJoinLocalGroup(
        @Body body: RequestJoinRequest
    ): Response<RequestJoinResponse>

    @POST("localgroup/invites/create/{groupId}")
    suspend fun createLocalGroupInviteLink(
        @Path("groupId") groupId: String,
        @Body body: com.example.myapplication.data.groups.model.CreateLocalGroupInviteLinkRequest
    ): Response<com.example.myapplication.data.groups.model.CreateLocalGroupInviteLinkResponse>

    @POST("localgroup/invites/accept")
    suspend fun joinLocalGroupByLink(
        @Body body: JoinGroupByLinkRequest
    ): Response<JoinGroupByLinkResponse>

    @GET("new-chatroom/list/summary")
    suspend fun getChatRoomSummary(
        @Query("roomCode") roomCode: String
    ): Response<GetChatRoomSummaryResponse>

    @POST("voice-room/create")
    suspend fun createVoiceRoom(
        @Query("chatRoomId") chatRoomId: String,
        @Query("roomName") roomName: String,
        @Query("createdBy") createdBy: String,
    ): Response<CreateVoiceRoomResponse>

    @GET("voice-room/list/{roomId}")
    suspend fun getVoiceRooms(
        @Path("roomId") roomId: String
    ): Response<GetAllVoiceRoomsResponse>

    @POST("voice-room/join")
    suspend fun joinVoiceRoom(
        @Query("janusRoomId") janusRoomId: Int,
        @Query("displayName") displayName: String
    ): Response<JoinVoiceRoomResponse>

    @DELETE("voice-room/delete")
    suspend fun deleteVoiceRoom(
        @Query("chatRoomId") chatRoomId: String,
        @Query("roomName") roomName: String,
        @Query("requester") requester: String
    ): Response<Unit>

    @DELETE("new-chatroom/{chatRoomCode}/delete")
    suspend fun deleteChatRoom(
        @Path("chatRoomCode") chatRoomCode: String,
        @Query("RoomCode") roomCode: String,
    ): Response<DeleteChatRoomResponse>


    @GET("community/exists")
    suspend fun checkCommunityNameExists(
        @Query("name") name: String
    ): Response<CheckCommunityNameResponse>

    @GET("local-group/exists")
    suspend fun checkLocalGroupNameExists(
        @Query("name") name: String
    ): Response<CheckLocalGroupNameResponse>



}
