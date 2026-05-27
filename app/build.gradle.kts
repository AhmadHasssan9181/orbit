plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
                freeCompilerArgs += "-Xskip-metadata-version-check"
            }
        }
    }

    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
                freeCompilerArgs += "-Xskip-metadata-version-check"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation(compose.materialIconsExtended)

                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

                // Multiplatform Settings
                implementation("com.russhwolf:multiplatform-settings-no-arg:1.1.1")
                implementation("com.russhwolf:multiplatform-settings-coroutines:1.1.1")

                implementation("io.ktor:ktor-client-core:3.0.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
                implementation("io.ktor:ktor-client-cio:3.0.1")
                implementation("io.ktor:ktor-client-logging:3.0.1")

                implementation("io.coil-kt.coil3:coil-compose:3.0.0")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.0")

                // Room KMP
                implementation("androidx.room:room-runtime:2.7.0-alpha11")
                implementation("androidx.sqlite:sqlite-bundled:2.5.0-alpha11")

                // Trixnity
                implementation("de.connect2x.trixnity:trixnity-client:5.4.0")
                implementation("de.connect2x.trixnity:trixnity-client-repository-room:5.4.0")
                implementation("de.connect2x.trixnity:trixnity-client-media-okio:5.4.0")
                implementation("de.connect2x.trixnity:trixnity-client-cryptodriver-vodozemac:5.4.0")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.0.1")
                implementation("androidx.activity:activity-compose:1.8.2")
                implementation("androidx.core:core-splashscreen:1.0.1")
                implementation("com.google.android.material:material:1.11.0")
                implementation("io.getstream:stream-webrtc-android:1.1.2")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-okhttp:3.0.1")
                // using project(":webrtc-java-lib")

                // JNA for WASAPI Audio Loopback
                implementation(project(":webrtc-java-lib"))
                implementation("net.java.dev.jna:jna:5.14.0")
                implementation("net.java.dev.jna:jna-platform:5.14.0")
                
                // VLCJ for Native Video Playback
                implementation("uk.co.caprica:vlcj:4.8.2")
                // Keep native dependencies if needed, or remove them if the DLL is loaded from resources
                // implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:windows-x86_64")
                // implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-x86_64")
            }
        }
    }
}

// KSP per-target (CRITICAL for KMP)
dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.7.0-alpha11")
    add("kspJvm", "androidx.room:room-compiler:2.7.0-alpha11")
}

android {
    namespace = "com.noobdev.orbit"
    compileSdk = 34

    defaultConfig {
        resourceConfigurations.addAll(listOf("en"))
        minSdk = 24
        targetSdk = 34
        applicationId = "com.noobdev.orbit"
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "ui.screen.MainKt"
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}