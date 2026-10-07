import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lyra para Windows: la misma música, biblioteca y estilo que en el móvil, en una ventana
// como la de Spotify. Comparte con el móvil todo lo de :core (YouTube Music, SoundCloud…).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Misma versión que la app del móvil: la cambia release.ps1 en app/build.gradle.kts.
val lyraVersionName: String = Regex("val lyraVersionName = \"([0-9.]+)\"")
    .find(rootProject.file("app/build.gradle.kts").readText())!!.groupValues[1]

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// La versión, accesible desde el código (BuildInfo.VERSION).
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val version = lyraVersionName
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/lyra/desktop/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.lyra.desktop
            |
            |object BuildInfo {
            |    const val VERSION = "$version"
            |    const val UPDATE_REPO = "toquedeseda/Lyra"
            |}
            |""".trimMargin(),
        )
    }
}
sourceSets.main { kotlin.srcDir(generateBuildInfo) }

dependencies {
    implementation(project(":core"))
    implementation(libs.compose.desktop.windows)
    implementation(libs.compose.mp.material3)
    implementation(libs.compose.mp.icons)
    implementation(libs.coroutines.swing)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    // FFmpeg para leer cualquier audio (M4A, WebM/Opus, MP3…) solo con las piezas de Windows.
    implementation(libs.javacpp)
    implementation(variantOf(libs.javacpp) { classifier("windows-x86_64") })
    implementation(libs.ffmpeg)
    implementation(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    // Las pruebas que van a internet solo con LYRA_LIVE_TESTS=1 (igual que en el móvil).
    environment("LYRA_LIVE_TESTS", System.getenv("LYRA_LIVE_TESTS") ?: "")
}

compose.desktop {
    application {
        mainClass = "com.lyra.desktop.MainKt"
        jvmArgs += listOf("-Xmx768m", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")

        // Al empaquetar se quitan los iconos que no se usan (ver proguard-rules.pro).
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            joinOutputJars.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Lyra"
            packageVersion = lyraVersionName
            description = "Lyra"
            vendor = "Lyra"
            copyright = "Lyra · GPL-3.0"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.sql", "jdk.crypto.ec", "jdk.dynalink", "jdk.unsupported", "jdk.accessibility")

            windows {
                iconFile.set(project.file("icons/lyra.ico"))
                // Sin pedir permisos de administrador: se instala solo para el usuario.
                perUserInstall = true
                dirChooser = false
                shortcut = true
                menu = true
                menuGroup = "Lyra"
                // No cambiar nunca: es lo que hace que una versión nueva sustituya a la anterior.
                upgradeUuid = "841c8c7c-b757-43e6-a210-e7346c4f92e3"
            }
        }
    }
}

// Al probar desde el código: datos aparte (%APPDATA%\Lyra-dev) y sin actualizaciones.
tasks.withType<JavaExec>().configureEach {
    systemProperty("lyra.dev", "1")
}
