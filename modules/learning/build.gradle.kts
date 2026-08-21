plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Room compiler needs kapt for Kotlin sources — same reasoning as
    // :modules:planner (InterventionDatabase) and :modules:psyche
    // (BehaviorDatabase); LearningDatabase below follows the same pattern.
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace  = "com.checkmate.learning"
    compileSdk = 35
    defaultConfig { minSdk = 26; targetSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // Added for Phase 1.4 (KnowledgeGraph) — needs ExamSyllabus (Exam -> Subject ->
    // Chapter -> Topic) as the seed source for Concept rows. Same dependency
    // direction :modules:planner and :modules:psyche already use (both depend on
    // :modules:core; core depends on neither).
    implementation(project(":modules:core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Used only for the List<String> Room TypeConverter (conceptIds/concepts) —
    // same Json instance pattern already used in :modules:psyche.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
