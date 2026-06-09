pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // Declare plugin versions (do not apply here). Modules will apply these IDs.
    plugins {
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
        id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack required for some GitHub-hosted libraries (ucrop)
        maven("https://jitpack.io")
        // Add WebRTC official maven repository so org.webrtc artifacts can be resolved
        maven("https://maven.webrtc.org")
        // Allow resolving local AAR files placed into app/libs when remote maven is unreachable
        // (flatDir must be added at the settings level because project repositories are disabled)
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "My Application"
include(":app")
