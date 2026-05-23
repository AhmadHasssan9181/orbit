pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // This fixes the JitPack/com.github errors!
        maven("https://jogamp.org/deployment/maven")
    }
}

rootProject.name = "Onichat"
include(":app")