import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Read local.properties safely
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.checkmate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.checkmate"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Telegram bot token — set in local.properties, never commit that file
        buildConfigField(
            "String",
            "TELEGRAM_BOT_TOKEN",
            "\"${localProps.getProperty("telegram_bot_token", "")}\""
        )

        // Testing backlog item 1 (baseline smoke test): AndroidJUnitRunner is the
        // default already applied by the AGP when this isn't set, but pinning it
        // explicitly here documents the assumption RoomSmokeTest /
        // MainActivitySmokeTest / ScreenInitializationSmokeTest below rely on —
        // connectedCheck runs androidTest through this runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ============================================================
    // PERMANENT CI SIGNING
    // ============================================================
    //
    // GitHub Actions supplies these through environment variables.
    //
    // Local builds without these variables continue to use the
    // normal Android debug signing configuration.
    //
    signingConfigs {
        create("checkmateCi") {
            val keystorePath = System.getenv("CHECKMATE_KEYSTORE")
            val keystorePassword = System.getenv("CHECKMATE_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("CHECKMATE_KEY_ALIAS")
            val keyPassword = System.getenv("CHECKMATE_KEY_PASSWORD")

            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }

            if (!keystorePassword.isNullOrBlank()) {
                storePassword = keystorePassword
            }

            if (!keyAlias.isNullOrBlank()) {
                this.keyAlias = keyAlias
            }

            if (!keyPassword.isNullOrBlank()) {
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            val ciKeystore = System.getenv("CHECKMATE_KEYSTORE")

            if (!ciKeystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("checkmateCi")
            }
        }
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

    // Testing backlog item 1: plain JVM unit tests (./gradlew test) run against the
    // android.jar stub, where every method throws "not mocked" by default. Matches
    // the same isReturnDefaultValues :modules:planner already sets for the same
    // reason (see its build.gradle.kts) — harmless here since app has no JVM tests
    // that depend on a real return value from a framework call.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
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

    // ============================================================
    // Testing backlog item 1 — baseline smoke test (JVM + instrumented)
    // ============================================================
    // Nothing below existed before: app/build.gradle.kts had no test
    // dependencies at all, even though ExampleInstrumentedTest.kt and
    // LearningInterventionOrchestratorIntegrationTest.kt already reference
    // androidx.test.ext.junit / InstrumentationRegistry — those two files were
    // only compiling because Gradle silently resolves androidTest sources
    // against whatever's on the main classpath plus AGP's implicit test deps;
    // this makes the requirement explicit and adds what RoomSmokeTest /
    // MainActivitySmokeTest / ScreenInitializationSmokeTest need on top.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // Drives MainScreen's Compose tree from MainActivitySmokeTest /
    // ScreenInitializationSmokeTest (click nav items, assert selection state).
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // RoomSmokeTest's three DB-open checks are suspend calls run via runTest.
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
