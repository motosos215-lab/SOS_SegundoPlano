val motoSosWebRegisterUrl = providers.gradleProperty("MOTOSOS_WEB_REGISTER_URL")
    .orNull
    ?.trim()
    .orEmpty()
val motoSosWebPasswordRecoveryUrl = providers.gradleProperty("MOTOSOS_WEB_PASSWORD_RECOVERY_URL")
    .orNull
    ?.trim()
    .orEmpty()
val motoSosWebContactsUrl = providers.gradleProperty("MOTOSOS_WEB_CONTACTS_URL")
    .orNull
    ?.trim()
    .orEmpty()
val motoSosWebContactsDashboardUrl = providers.gradleProperty("MOTOSOS_WEB_CONTACTS_DASHBOARD_URL")
    .orNull
    ?.trim()
    .orEmpty()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.sos_segundoplano"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.sos_segundoplano"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "MOTOSOS_API_BASE_URL",
            "\"https://motosos-api-tcrg6.ondigitalocean.app/\""
        )
        buildConfigField(
            "String",
            "MONITOR_ROUTING_BASE_URL",
            "\"https://router.project-osrm.org/\""
        )
        buildConfigField(
            "String",
            "MOTOSOS_WEB_REGISTER_URL",
            "\"${motoSosWebRegisterUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
        buildConfigField(
            "String",
            "MOTOSOS_WEB_PASSWORD_RECOVERY_URL",
            "\"${motoSosWebPasswordRecoveryUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
        buildConfigField(
            "String",
            "MOTOSOS_WEB_CONTACTS_URL",
            "\"${motoSosWebContactsUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
        buildConfigField(
            "String",
            "MOTOSOS_WEB_CONTACTS_DASHBOARD_URL",
            "\"${motoSosWebContactsDashboardUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.wearable)
    implementation(project(":wear-protocol"))
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.core)
    implementation(libs.moshi.kotlin)
    implementation(libs.maplibre.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
