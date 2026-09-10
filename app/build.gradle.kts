import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Read local.properties safely
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.checkmate"
    compileSdk = 35
    signingConfigs {
    create("checkmateCi") {
        val keystorePath = System.getenv("CHECKMATE_KEYSTORE")
        val keystorePassword = System.getenv("CHECKMATE_KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("CHECKMATE_KEY_ALIAS")
        val keyPassword = System.getenv("CHECKMATE_KEY_PASSWORD")

        if (
            !keystorePath.isNullOrBlank() &&
            !keystorePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() &&
            !keyPassword.isNullOrBlank()
        ) {
            storeFile = file(keystorePath)
            storePassword = keystorePassword
            this.keyAlias = keyAlias
            this.keyPassword = keyPassword
        }
    }
}

    defaultConfig {
        applicationId = "com.checkmate"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        signingConfig = signingConfigs.getByName("checkmateCi")
        // Telegram bot token — set in local.properties, never commit that file
        buildConfigField(
            "String",
            "TELEGRAM_BOT_TOKEN",
            "\"${localProps.getProperty("telegram_bot_token", "")}\""
            
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(project(":modules:core"))
    implementation(project(":modules:automation"))
    implementation(project(":modules:callhandler"))
    implementation(project(":modules:workmode"))
    implementation(project(":modules:psyche"))
    implementation(project(":modules:planner"))
    implementation(project(":modules:testmate"))
    implementation(project(":modules:learning"))
}
