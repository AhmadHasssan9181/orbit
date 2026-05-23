plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")

    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.application")
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

                // Serialization & Core
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

                // Ktor Common Network
                implementation("io.ktor:ktor-client-core:3.0.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")

                // Trixnity Matrix SDK Core
                implementation("de.connect2x.trixnity:trixnity-client:5.4.0")


                implementation("io.coil-kt.coil3:coil-compose:3.0.0")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.0")

                implementation("io.ktor:ktor-client-cio:3.0.1")
                implementation("io.ktor:ktor-client-logging:3.0.1")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.0.1")
                implementation("androidx.activity:activity-compose:1.8.2")
                implementation("androidx.core:core-splashscreen:1.0.1")
                implementation("com.google.android.material:material:1.11.0")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-okhttp:3.0.1")
            }
        }
    }
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