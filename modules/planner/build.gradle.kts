plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace  = "com.checkmate.planner"
    compileSdk = 35
    defaultConfig { minSdk = 26; targetSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":modules:core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    // Added for TaskEscrow's concurrency tests (Proactive Execution Engine step 4) — runs
    // suspend test functions via runTest(); version matched to the existing
    // kotlinx-coroutines-android dependency above.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
