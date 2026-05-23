plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false

    // DOWNGRADE THESE TO 2.0.21
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false

    // Leave this alone
    id("org.jetbrains.compose") version "1.7.3" apply false

    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("androidx.room") version "2.7.0-alpha11" apply false
}