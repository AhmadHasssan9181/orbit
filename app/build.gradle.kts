plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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

                implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

                implementation("io.ktor:ktor-client-core:3.0.1")
                implementation("io.ktor:ktor-client-logging:3.0.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
                implementation("io.ktor:ktor-client-websockets:3.0.1")
                implementation("io.ktor:ktor-client-cio:3.0.1")
                implementation("io.ktor:ktor-client-okhttp:3.0.1")

                implementation("com.mikepenz:aboutlibraries-core:11.2.3")

                implementation("dev.chrisbanes.haze:haze:1.1.0")
                implementation("dev.chrisbanes.haze:haze-materials:1.1.0")

                implementation("org.jetbrains:markdown:0.7.3")

                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
                implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.27.0")

                implementation("io.coil-kt.coil3:coil-compose:3.0.4")
                implementation("com.russhwolf:multiplatform-settings:1.1.1")
                implementation("com.russhwolf:multiplatform-settings-no-arg:1.1.1")
                
                // Matrix SDK
                implementation("net.folivo:trixnity-client:4.22.7")
                implementation("net.folivo:trixnity-client-media-okio:4.22.7")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)

                implementation("io.ktor:ktor-client-okhttp:3.0.1")
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("2.1.0")
        }
    }
}

compose.desktop {
    application {
        mainClass = "ui.screen.MainKt"
    }
}
