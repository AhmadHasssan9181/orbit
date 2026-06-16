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
                implementation("com.google.protobuf:protobuf-java:4.29.3")
                
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

val orbitAudioJvmArgs = listOf(
    "orbit.audio.testTone",
    "orbit.audio.testToneStereo",
    "orbit.audio.inputDeviceName",
    "orbit.audio.captureGain",
    "orbit.audio.selfTestDurationMs",
    "orbit.audio.autoProbeDurationMs",
    "orbit.livekit.audioSourceQueueMs",
    "orbit.livekit.dumpTonePublisherEnv",
    "orbit.matrixrtc.publishLegacyMember",
    "orbit.matrixrtc.publishMemberMode",
    "orbit.matrixrtc.mirrorVisibleElementShape"
).mapNotNull { propertyName ->
    System.getProperty(propertyName)
        ?.takeIf { it.isNotBlank() }
        ?.let { "-D$propertyName=$it" }
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

compose.desktop {
    application {
        mainClass = "ui.screen.MainKt"
        jvmArgs += listOf(
            "--add-opens", "java.base/java.lang=ALL-UNNAMED"
        )
        jvmArgs += orbitAudioJvmArgs
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(orbitAudioJvmArgs)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Custom task to compile the C++ JNI bridge for the JVM target
tasks.register("compileJniBridge") {
    doLast {
        val cppDir = file("src/jvmMain/cpp")
        val buildDir = file("build/jni")
        buildDir.mkdirs()
        
        exec {
            commandLine("cmake", "-S", cppDir.absolutePath, "-B", buildDir.absolutePath)
        }
        exec {
            commandLine("cmake", "--build", buildDir.absolutePath, "--config", "Release")
        }
        
        // Copy native bridge DLLs to the resources folder where the JVM loader can find them.
        val targetDir = file("src/jvmMain/resources/webrtc-deps")
        targetDir.mkdirs()

        fun copyBuiltDll(dllName: String) {
            val outputDll = file("build/jni/Release/$dllName")
            if (!outputDll.exists()) {
                // Fallback for non-Release builds or different generators.
                file("build/jni").walkTopDown().find { it.name == dllName }?.let {
                    it.copyTo(targetDir.resolve(dllName), overwrite = true)
                }
            } else {
                outputDll.copyTo(targetDir.resolve(dllName), overwrite = true)
            }
        }

        copyBuiltDll("orbit_livekit_video.dll")
        copyBuiltDll("orbit_livekit_audio.dll")

        // The prebuilt LiveKit C++ SDK ships runtime DLLs next to its package.
        file("build/jni/_deps/livekit-sdk").walkTopDown()
            .filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
            .forEach { sdkDll ->
                val targetDll = targetDir.resolve(sdkDll.name)
                try {
                    sdkDll.copyTo(targetDll, overwrite = true)
                } catch (existing: FileAlreadyExistsException) {
                    if (targetDll.exists() && targetDll.length() == sdkDll.length()) {
                        println("Skipping unchanged LiveKit SDK DLL already in resources: ${sdkDll.name}")
                    } else {
                        throw existing
                    }
                }
            }
    }
}

tasks.register("runLiveKitTonePublisher") {
    dependsOn("compileJniBridge")

    doLast {
        val url = (findProperty("livekitUrl") as String?) ?: System.getenv("ORBIT_LIVEKIT_URL")
        val token = (findProperty("livekitToken") as String?) ?: System.getenv("ORBIT_LIVEKIT_TOKEN")
        val seconds = ((findProperty("toneSeconds") as String?)
            ?: System.getenv("ORBIT_LIVEKIT_TONE_SECONDS")
            ?: "120")
        val channels = ((findProperty("toneChannels") as String?)
            ?: System.getenv("ORBIT_LIVEKIT_TONE_CHANNELS")
            ?: "2")

        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            throw GradleException(
                "Set ORBIT_LIVEKIT_URL and ORBIT_LIVEKIT_TOKEN, or pass -PlivekitUrl=... -PlivekitToken=..."
            )
        }

        val exeName = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
            "orbit_livekit_tone_publisher.exe"
        } else {
            "orbit_livekit_tone_publisher"
        }
        val candidates = listOf(
            file("build/jni/Release/$exeName"),
            file("build/jni/$exeName")
        )
        val executable = candidates.firstOrNull { it.exists() }
            ?: throw GradleException("Cannot find $exeName. Run :app:compileJniBridge first.")

        exec {
            environment("ORBIT_LIVEKIT_URL", url)
            environment("ORBIT_LIVEKIT_TOKEN", token)
            environment("ORBIT_LIVEKIT_TONE_SECONDS", seconds)
            environment("ORBIT_LIVEKIT_TONE_CHANNELS", channels)
            commandLine(executable.absolutePath)
        }
    }
}

// Ensure JNI bridge is compiled before running or assembling
tasks.named("jvmProcessResources") {
    dependsOn("compileJniBridge")
}
