plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
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

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
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

// KSP for the JVM target
dependencies {
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
    "orbit.audio.dumpPlayback",
    "orbit.matrixrtc.publishLegacyMember",
    "orbit.matrixrtc.publishMemberMode",
    "orbit.matrixrtc.mirrorVisibleElementShape",
    "orbit.apm.enabled",
    "orbit.apm.delayMs",
    "orbit.video.inputDeviceName",
    "orbit.video.inputDeviceIndex",
    "orbit.video.requestedWidth",
    "orbit.video.requestedHeight",
    "orbit.video.requestedFps",
    "orbit.video.publish",
    "orbit.video.updateMembershipAfterPublish",
    "orbit.video.membershipMode",
    "orbit.video.remoteRender"
).mapNotNull { propertyName ->
    System.getProperty(propertyName)
        ?.takeIf { it.isNotBlank() }
        ?.let { "-D$propertyName=$it" }
}

compose.desktop {
    application {
        mainClass = "ui.screen.MainKt"
        jvmArgs += listOf(
            "-Xmx2048m",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED"
        )
        jvmArgs += orbitAudioJvmArgs

        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "Orbit"
            packageVersion = "0.1.5"
            description = "Orbit — Matrix voice/video calls client"
            vendor = "Orbit"

            // Trixnity/Ktor/Koin/Room rely on runtime services + reflection;
            // module detection misses them, so ship everything.
            includeAllModules = true

            windows {
                menu = true
                menuGroup = "Orbit"
                shortcut = true
                dirChooser = true
                // Per-user install (no admin/UAC) — friends can just double-click.
                perUserInstall = true
                upgradeUuid = "7f3c2a10-52d4-4b8e-9c61-0a5e8d2f4b17"
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    // Video calls generate hundreds of MB/s of short-lived frame buffers; without
    // a cap the default max heap (1/4 of physical RAM) lets RSS balloon past 15 GB
    // before G1 collects. The live set is well under 1 GB.
    jvmArgs("-Xmx2048m")
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
        buildDir.deleteRecursively()
        buildDir.mkdirs()
        
        val targetDir = file("src/jvmMain/resources/webrtc-deps")
        targetDir.mkdirs()
        val audioDllExists = targetDir.resolve("orbit_livekit_audio.dll").exists()
        val videoDllExists = targetDir.resolve("orbit_livekit_video.dll").exists()

        var vsBat = listOf(
            file("D:/vs studio/VC/Auxiliary/Build/vcvars64.bat"),
            file("D:/vs studio/Common7/Tools/VsDevCmd.bat"),
            file("D:/Visual Studio/VC/Auxiliary/Build/vcvars64.bat"),
            file("D:/Microsoft Visual Studio/VC/Auxiliary/Build/vcvars64.bat"),
            file("C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Auxiliary/Build/vcvars64.bat"),
            file("C:/Program Files/Microsoft Visual Studio/2022/Enterprise/VC/Auxiliary/Build/vcvars64.bat"),
            file("C:/Program Files/Microsoft Visual Studio/2022/Professional/VC/Auxiliary/Build/vcvars64.bat"),
            file("C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Auxiliary/Build/vcvars64.bat")
        ).firstOrNull { it.exists() }

        if (vsBat == null && System.getProperty("os.name").lowercase().contains("windows")) {
            runCatching {
                val dDrive = file("D:/")
                if (dDrive.exists()) {
                    vsBat = dDrive.walkTopDown().maxDepth(6).firstOrNull { it.name.equals("vcvars64.bat", ignoreCase = true) && !it.absolutePath.contains("vsstudioorig", ignoreCase = true) }
                        ?: dDrive.walkTopDown().maxDepth(6).firstOrNull { it.name.equals("vcvars64.bat", ignoreCase = true) }
                }
            }
        }

        val vsBatFile = vsBat
        try {
            if (vsBatFile != null && System.getProperty("os.name").lowercase().contains("windows")) {
                println("DEBUG [compileJniBridge] Using Visual Studio environment: ${vsBatFile.absolutePath}")
                exec {
                    commandLine("cmd", "/c", "\"${vsBatFile.absolutePath}\" && cmake -S \"${cppDir.absolutePath}\" -B \"${buildDir.absolutePath}\" -DCMAKE_BUILD_TYPE=Release -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreadedDLL && cmake --build \"${buildDir.absolutePath}\" --config Release")
                }
            } else {
                exec {
                    commandLine("cmake", "-S", cppDir.absolutePath, "-B", buildDir.absolutePath, "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreadedDLL")
                }
                exec {
                    commandLine("cmake", "--build", buildDir.absolutePath, "--config", "Release")
                }
            }
        } catch (e: Exception) {
            if (audioDllExists && videoDllExists) {
                println("WARN: [compileJniBridge] Native C++ build failed (${e.message}). Using existing prebuilt DLLs in resources.")
                return@doLast
            } else {
                throw GradleException("Failed to compile C++ JNI bridge and no prebuilt DLLs found in resources: ${e.message}", e)
            }
        }
        
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
