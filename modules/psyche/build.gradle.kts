plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Upgrade Blueprint Phase 0 item #3 ("Confirm Room is single source of truth"):
    // added alongside the new BehaviorDatabase/BehaviorEventEntity/BehaviorEventDao —
    // Room's compiler needs kapt for Kotlin sources (annotationProcessor only handles
    // Java, which is why :modules:core's existing Room deps are effectively dead —
    // see its build.gradle.kts). :modules:planner already applies this same plugin
    // for InterventionDatabase; same pattern here.
    id("org.jetbrains.kotlin.kapt")
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
    // Upgrade Blueprint Phase 0 item #3: Room for BehaviorDatabase (see its own doc) —
    // same versions :modules:planner already pins for InterventionDatabase.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // Added for ContextBuilderTest (Proactive Execution Engine step 8) — same gap noted
    // in modules/planner previously: no testImplementation("junit:...") existed here
    // either. ContextBuilder itself is fully synchronous, so no coroutines-test needed.
    testImplementation("junit:junit:4.13.2")
}
