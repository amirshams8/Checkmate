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
    // Plain JVM unit tests run against the android.jar stub, where every method throws
    // "not mocked" by default. isReturnDefaultValues covers void/no-op calls like
    // android.util.Log.d used along the orchestrator's logging paths — nothing in this
    // module's tests depends on Log's return value. It does NOT fix org.json (see below):
    // that would make JSONObject.optString silently return its default arg instead of
    // actually parsing, which would make LlmIntentParserTest's assertions meaningless.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    implementation(project(":modules:core"))
    // Upgrade Blueprint Phase 2 wiring: AdaptivePlanner now reads StudentModelBuilder.build()
    // (mastery/error/retention/prerequisite state) instead of only PYQ weightage + behavior
    // snapshot. Same dependency direction :modules:learning already takes on :modules:core —
    // :modules:learning depends on neither :modules:planner nor :modules:psyche, so this
    // doesn't introduce a cycle.
    implementation(project(":modules:learning"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // Added for the Proactive Trigger Engine (step 7) — no WorkManager dependency
    // existed anywhere in the project before this; everything else here uses
    // AlarmManager. This is a deliberate blueprint choice (§2): WorkManager for durable
    // periodic evaluation, AlarmManager reserved for precise per-task timing (not yet
    // built — see InterventionTriggerScheduler's doc comment on why it needs no
    // BootReceiver re-arming, unlike this app's existing AlarmManager schedules).
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Real org.json impl for the JVM test classpath — LlmIntentParserTest and
    // InterventionFallbackTest exercise actual JSONObject parsing, and the android.jar
    // stub's org.json classes throw "not mocked" on every call rather than returning
    // usable defaults.
    testImplementation("org.json:json:20231013")
}
