plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Gradle's Windows test worker can misdecode a non-ASCII classpath.
// Stage only affected classpath entries; source and APK output remain in the project.
tasks.withType<Test>().configureEach {
    doFirst {
        val stage = File(System.getProperty("java.io.tmpdir"), "tongye-test-classpath/${rootProject.projectDir.absolutePath.hashCode()}")
        stage.mkdirs()
        classpath = files(classpath.files.filter { it.exists() }.mapIndexed { index, entry ->
            if (entry.absolutePath.any { it.code > 127 }) {
                val copy = File(stage, "$index-${entry.name}")
                entry.copyRecursively(copy, overwrite = true)
                copy
            } else entry
        })
    }
}
// Exercise the real disk transport with far less heap than the test archives themselves.
tasks.register<Test>("streamingLowMemory") {
    val unit=tasks.named<Test>("testDebugUnitTest")
    dependsOn(unit)
    testClassesDirs=unit.get().testClassesDirs
    classpath=unit.get().classpath
    filter {includeTestsMatching("app.tongye.diary.StreamingFilesTest")}
    maxHeapSize="96m"
    jvmArgs("-Dfile.encoding=UTF-8")
}
android {
    namespace = "app.tongye.diary"
    compileSdk = 36
    defaultConfig { applicationId = "app.tongye.diary"; minSdk = 26; targetSdk = 36; versionCode = 10; versionName = "0.2.7" }
    // A second isolated installation allows end-to-end exchange tests on one emulator.
    if (providers.gradleProperty("tongyePeerTest").orNull == "true") { defaultConfig {applicationIdSuffix=".peer"} }
    defaultConfig {resValue("string","app_name",if(providers.gradleProperty("tongyePeerTest").orNull=="true") "同页·测试TA" else "同页")}
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    testOptions { unitTests.all { it.jvmArgs("-Dfile.encoding=UTF-8");it.systemProperty("tongye.testArtifacts",rootProject.file("deliverables/verification/exchange").absolutePath) } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
