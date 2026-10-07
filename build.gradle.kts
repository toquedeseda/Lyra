plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 trae Kotlin integrado (2.2.10). Declarar el plugin sin aplicarlo
    // fija la versión de Kotlin que usa ese Kotlin integrado.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
