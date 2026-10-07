import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lo que comparten la app del móvil y la de Windows: fuentes de música (YouTube Music y
// SoundCloud), letras, títulos limpios, playlists compartidas… Sin nada de Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)
    api(libs.newpipe.extractor)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
