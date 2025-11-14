plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // BuildConfig for API base URL
        buildConfigField("String", "BASE_URL", "\"${project.findProperty("BASE_URL")}\"")
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Enable core library desugaring for java.time support on older devices
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        jvmToolchain(11)
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.swiperefreshlayout)

    // Retrofit and networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.jwt.decode)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // DataStore for modern data persistence
    implementation(libs.androidx.datastore.preferences)

    // Room Database for complex data (communities, posts, etc.)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.glimmer)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.ui.test)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)

    implementation("com.github.bumptech.glide:glide:4.14.2")
    ksp("com.github.bumptech.glide:ksp:4.14.2")

    // OkHttp integration for Glide (for authenticated requests)
    implementation("com.github.bumptech.glide:okhttp3-integration:4.14.2")

    // WebSocket for real-time chat with SockJS and STOMP support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // RxJava needed for StompProtocolAndroid
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")

    // WebRTC: prefer remote maven (configured in settings.gradle.kts) but also allow local AAR fallback (place .aar into app/libs)
    // If network cannot reach maven.webrtc.org, put google-webrtc-1.0.32006.aar in app/libs and Gradle will pick it up via files(...) below.
    // Use local AAR to avoid remote repo resolution when offline/unreachable
    implementation(files("libs/google-webrtc-1.0.32006.aar"))
    // NOTE: keep remote artifact commented out to avoid Gradle attempting to fetch it during offline builds
    // implementation("org.webrtc:google-webrtc:1.0.32006")

    implementation("com.github.NaikSoftware:StompProtocolAndroid:1.6.6")

}