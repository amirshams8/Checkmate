plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace  = "com.checkmate.psyche"
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
    implementation(project(":modules:planner"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // Added for ContextBuilderTest (Proactive Execution Engine step 8) — same gap noted
    // in modules/planner previously: no testImplementation("junit:...") existed here
    // either. ContextBuilder itself is fully synchronous, so no coroutines-test needed.
    testImplementation("junit:junit:4.13.2")
}
