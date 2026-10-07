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
        // NewPipeExtractor se publica en JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "Lyra"
include(":app")
// Lo común al móvil y al PC: YouTube Music, SoundCloud, letras, modelos…
include(":core")
// Lyra para Windows (Compose Desktop).
include(":desktop")
