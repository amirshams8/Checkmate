plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
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
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    // Added for PolicyValidator's policy test matrix (Proactive Execution Engine step 1/2).
    // No testImplementation("junit:...") existed anywhere in the project despite
    // ExampleUnitTest.kt importing org.junit — added explicitly rather than assuming a
    // root-level convention plugin supplies it.
    testImplementation("junit:junit:4.13.2")
}
