Voice Room — build and testing guide

This document explains how to resolve the Gradle/WebRTC issues you're seeing, how to run and test the voice room feature end-to-end, and a short checklist of small code improvements you can apply.

1) Why the build fails (summary)
- The project depends on the Google WebRTC AAR (org.webrtc:google-webrtc:1.0.32006). Gradle must either download it from the official maven (https://maven.webrtc.org) or find a local copy in `app/libs` (flatDir).
- Typical errors:
  * "WebRTC artifact resolution failed: host 'maven.webrtc.org' is not reachable and local AAR not found" — means Gradle couldn't fetch the artifact and there isn't a local AAR.
  * "No cached version ... available for offline mode" — you are building in Gradle offline mode or the artifact hasn't been cached.
  * "Build was configured to prefer settings repositories over project repositories but repository 'flatDir' was added by build file 'app\build.gradle.kts'" — happens if `flatDir` was added in a module build file while the settings file already enforces repository policies. The project currently puts `flatDir` into `settings.gradle.kts` (this is correct). Just make sure there is no other `flatDir` in module build files.

2) Quick fixes — pick one

A) Preferred: let Gradle fetch it from the official repo
- Make sure you have an internet connection or your proxy is configured.
- Disable Gradle offline mode in Android Studio: File -> Settings -> Build, Execution, Deployment -> Gradle -> uncheck "Offline work".
- Run a Gradle refresh so dependencies are resolved:

```cmd
cd C:\Users\LENOVO\AndroidStudioProjects\SpaceHub-app
gradlew.bat clean assembleDebug --refresh-dependencies
```

B) If you cannot reach maven.webrtc.org: add the AAR locally
- Download the AAR file (example URL):
  https://maven.webrtc.org/org/webrtc/google-webrtc/1.0.32006/google-webrtc-1.0.32006.aar
- Place the file into `app/libs/` so the path is `app/libs/google-webrtc-1.0.32006.aar`.
- The project already contains this fallback line in `app/build.gradle.kts`:
  implementation(files("libs/google-webrtc-1.0.32006.aar"))
- Re-run Gradle (make sure offline mode is disabled if you rely on other remote dependencies).

Note: If you still see complaints about "extractedFolder=null" it usually means Gradle picked up the AAR file but failed processing it (rare). Removing build caches sometimes helps:

```cmd
gradlew.bat clean build --refresh-dependencies
```

3) Repositories configuration (what to check)
- `settings.gradle.kts` already adds the WebRTC maven and also `flatDir` pointing at `app/libs`. That's the correct place because `dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` is enabled.
- Make sure you DO NOT add another `flatDir {}` inside `app/build.gradle.kts` — that causes the specific error about preferring settings repositories. If you have older edits that added `repositories { flatDir { dirs("libs") } }` in the module gradle file, remove it.

4) If unresolved references remain at compile time
- Those come from the Kotlin files that import `org.webrtc.*` (e.g. `VoicePeerManager.kt`). Fix by ensuring the AAR is present or the remote maven reachable (see steps above). The source files are correct and assume the library is available.

5) How to run and test the Voice Room feature (end-to-end)

Prerequisites
- Backend STOMP server: the app's voice implementation uses STOMP endpoints like `/app/register`, `/app/offer`, `/app/ice`, `/app/answer`, `/app/mute`, `/app/speaking`, `/app/stopped_speaking`. You must run the project's backend (or a compatible signaling server) before attempting a full E2E test.
- Two clients (two phones or one phone + one emulator) logged in as different users to join the same room ID.

Steps (manual, two-device)
1. Build and install the app on two devices (or two emulator instances):

```cmd
cd C:\Users\LENOVO\AndroidStudioProjects\SpaceHub-app\
gradlew.bat installDebug
```

2. On both devices sign in with different user accounts.
3. On device A create (or join) a voice room. Note the room identifier (roomId/roomCode) used by the server.
4. On device B join the same room using the same roomId/roomCode.

Expected flow
- Device A will create an SDP offer and send `/app/offer` (STOMP) to the server. The backend should forward that offer to device B (or the Janus gateway) and device B should create an answer and send it back. Each device should exchange ICE candidates over `/app/ice`.
- Logs to observe (tagged in code with `Log.d(TAG, ...)`):
  * "PeerConnectionFactory initialized"
  * "Local audio track created"
  * "PeerConnection created" and "Offer created"
  * "Local ICE candidate: ..." when local ICE is gathered
  * "Remote description set successfully" when the answer is applied
- UI indicators:
  * Other participant joins should be displayed in the members grid.
  * The mute toggle should mute/unmute the local audio track.
  * Speaking indicator is driven by `LocalAudioLevelDetector` which samples mic levels and sends speaking/stopped_speaking messages.

Emulator tips
- The standard Android emulator supports microphone input if the AVD configuration uses host audio. Using a physical device is more reliable for audio testing.
- If you test with one device + a second device running on the same machine (like two emulators), ensure both are connected to the backend server and both users are logged in.

Testing without a backend (local smoke test)
- You can create a quick local stub that echoes STOMP messages back; however, because signaling requires proper offer/answer handling the quickest way is to run the real backend or a Janus gateway that you already use in production.

6) Useful log checks and debugging
- In `Logcat` filter by the tag used in the code (`VoicePeerManager`, `VoiceRoomFragment`, `LocalAudioLevelDetector`) and also watch for `org.webrtc.*` logs.
- If you see errors about missing classes (NoClassDefFoundError or ClassNotFoundException for org.webrtc.*) then your AAR resolution step was unsuccessful — revisit step 2.
- If peer connection fails to establish:
  * Check ICE candidates are being generated and sent to the other peer
  * Confirm server relays candidates correctly
  * Confirm firewall/NAT/port issues if peers are on different networks (use TURN server if required)

7) Code improvements and safeguards you can add (small, low-risk)
- Add a runtime check at app start to verify that `org.webrtc.PeerConnectionFactory` is available (Class.forName("org.webrtc.PeerConnectionFactory") inside a try). If not present, show a helpful message explaining how to add the AAR or enable internet to fetch the dependency.
- Add UI state when the peer setup fails — show a toast and disable voice controls.
- Add idempotent checks when creating chat rooms on the server side to prevent duplicate default rooms from being auto-created. The client should call "get or create" rather than always "create". On the client, avoid blindly calling create if the server already returned a room for the current user. (I can help implement this change if you point me to the code path that creates the default chat room.)

8) Run commands summary (Windows cmd)
- Build and run with dependency refresh (recommended):

```cmd
cd C:\Users\LENOVO\AndroidStudioProjects\SpaceHub-app
gradlew.bat clean assembleDebug --refresh-dependencies
```

- Install to a connected device/emulator:

```cmd
gradlew.bat installDebug
```

- If you need to re-run with a freshly added AAR in `app/libs`, run:

```cmd
gradlew.bat clean --refresh-dependencies assembleDebug
```

9) If you'd like, I can do the following for you now:
- Add a brief runtime availability check (Class.forName) to `VoiceRoomFragment` that: if org.webrtc classes are not found, shows a toast that tells you to add the AAR or enable network so Gradle can fetch it. This is a non-breaking change and compiles regardless of AAR presence.
- Implement a small test-mode `NoOpPeerManager` and wiring so you can exercise the UI and speaking indicator locally without performing real peer connections (useful for UI+UX testing).

Tell me which of the optional code changes above you'd like me to apply now (runtime check, NoOp peer manager, or fix the duplicate-chat-room creation path). If you want me to apply code changes I'll make them and run quick checks.

---
Checklist (what I did now)
- Added this in-repo `app/VOICE_ROOM_README.md` with guidance to fix build issues, and a step-by-step voice-room testing guide. 

Requirements coverage
- Help resolve WebRTC build errors: Done (instructions + quick commands). Actual AAR cannot be downloaded by me; I explained how to place it in `app/libs` or let Gradle fetch it. (Deferred: I cannot download external AAR in this environment.)
- Complete implementation of voice room: The app's UI and logic (fragment+peer manager+audio detector) are present; the README explains final runtime requirements (signaling server and AAR). I offered to add runtime checks and a NoOp peer manager — tell me which you'd like and I'll implement it.


