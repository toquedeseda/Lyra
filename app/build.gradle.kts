import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// La versión la cambia el script release.ps1: no tocar el formato de estas dos líneas.
val lyraVersionCode = 16
val lyraVersionName = "1.11.0"

// Datos de firma de las releases (fuera del repositorio, ver README).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.lyra.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lyra.music"
        minSdk = 26
        targetSdk = 36
        versionCode = lyraVersionCode
        versionName = lyraVersionName

        // Repositorio de GitHub donde se publican las versiones (autoactualización).
        buildConfigField("String", "UPDATE_REPO", "\"toquedeseda/Lyra\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // Dos versiones con el mismo paquete (se pueden instalar una encima de la otra):
    //  - completa: la isla puede ir junto a la cámara (necesita el permiso de accesibilidad).
    //  - amigos: sin ese permiso, para que Google Play Protect no la bloquee al instalarla.
    // Cada una se actualiza con su archivo de la release (Lyra-vX.apk / Lyra-vX_amigos.apk).
    flavorDimensions += "version"
    productFlavors {
        create("completa") {
            dimension = "version"
            buildConfigField("boolean", "ISLAND_NEXT_TO_CAMERA", "true")
            buildConfigField("String", "UPDATE_ASSET", "\"completa\"")
        }
        create("amigos") {
            dimension = "version"
            buildConfigField("boolean", "ISLAND_NEXT_TO_CAMERA", "false")
            buildConfigField("String", "UPDATE_ASSET", "\"amigos\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor usa APIs de Java que no existen en Android antiguo.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.newpipe.extractor)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
