plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.jashith.stride"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jashith.stride"
        minSdk = 26
        targetSdk = 37
        versionCode = (System.getenv("STRIDE_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("STRIDE_VERSION_NAME") ?: "1.0.0"
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
