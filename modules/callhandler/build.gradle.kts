plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace  = "com.checkmate.callhandler"
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
}
