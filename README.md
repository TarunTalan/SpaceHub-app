# SpaceHub

![SpaceHub Banner](placeholder_banner.png)

## Overview
SpaceHub is a collaborative platform that unites teams through organized chat rooms, real-time voice rooms, and secure file sharing within a workspace. Built with modern Android development practices, this app demonstrates scalable architecture and real-time communication capabilities.

## 🚀 Features
*   **Authentication & Onboarding**: Secure login, multi-step signup, OTP-based password recovery, and profile setup (username & avatar).
*   **Communities & Workspaces**: Create, join, and manage large-scale communities with dedicated overview and member management screens.
*   **Local Groups**: Specialized group spaces with detailed descriptions and member roles.
*   **Real-Time Chat Rooms**: Create and participate in dedicated text chat rooms within communities.
*   **Voice Rooms**: Real-time audio communication powered by WebRTC for seamless team collaboration.
*   **Direct Messaging**: Private 1-on-1 chats with real-time text messaging between friends.
*   **Friend System**: Search for users, send/receive friend requests, and manage an active friends list.
*   **Profile Management**: Dedicated profile screens to update avatars, usernames, and personal details.
*   **Notifications & Alerts**: Stay updated with a dedicated notifications hub for requests and messages.

## 📸 Screenshots

| Onboarding | Home / Workspace | Chat Room |
|:---:|:---:|:---:|
| <img src="placeholder_screenshot_1.png" width="250"> | <img src="placeholder_screenshot_2.png" width="250"> | <img src="placeholder_screenshot_3.png" width="250"> |

## 🛠 Tech Stack & Libraries
*   **Language & UI:** Kotlin, ViewBinding, and Jetpack Compose integration.
*   **Architecture:** MVVM (Model-View-ViewModel), Android Architecture Components.
*   **Navigation:** Android Jetpack Navigation Component (Single-Activity Architecture).
*   **Networking & APIs:** Retrofit2, OkHttp3 (with interceptors for JWT Auth).
*   **Real-Time Communication:** WebRTC (`google-webrtc`) for voice rooms, and WebSockets for live chat features.
*   **Local Persistence:** Room Database for caching messages/data, DataStore for secure preference storage.
*   **Image Loading:** Glide (with OkHttp integration for authenticated image fetching).
*   **Concurrency:** Kotlin Coroutines & Flows for reactive, asynchronous operations.

## 💡 Technical Highlights
*   **Real-time Collaboration:** Implemented robust WebSockets for live messaging and WebRTC for low-latency audio transmission.
*   **Scalable Architecture:** Maintained a clean MVVM structure enforcing separation of concerns between UI, Business Logic, and Data layers.
*   **Robust Security:** Secure handling of authentication using JWT tokens stored securely via Jetpack DataStore.
*   **Offline Support Capabilities:** Utilizing Room Database to cache data, ensuring a smooth experience even on unstable networks.

## ⚙️ Getting Started

### Prerequisites
*   Android Studio (latest recommended)
*   JDK 17
*   Android SDK

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/SpaceHub-app.git
   ```
2. Open the project in Android Studio.
3. Sync Project with Gradle Files.
4. Run the `app` configuration on an emulator or physical device.

## 📬 Contact
**[Your Name]**
* LinkedIn: [Your Profile](https://linkedin.com/in/)
* GitHub: [@your-username](https://github.com/)
* Email: your.email@example.com
