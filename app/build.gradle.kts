plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "app.octopus.local"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.octopus.local"
        minSdk = 28
        targetSdk = 35
        // Test-only older identity exercises a real upgrade to the currently published signed APK.
        // Release validation rejects this identity; no fixture flag is used by CI publishing.
        val updateFixture = providers.gradleProperty("octopusUpdateFixture").orNull == "true"
        versionCode = if (updateFixture) 13 else 27
        versionName = if (updateFixture) "1.3.5" else "1.3.19"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val previewStoreFile = System.getenv("OCTOPUS_SIGNING_STORE_FILE")
    signingConfigs {
        if (!previewStoreFile.isNullOrBlank()) create("stablePreview") {
            storeFile = file(previewStoreFile)
            storePassword = System.getenv("OCTOPUS_SIGNING_STORE_PASSWORD") ?: error("Missing signing store password")
            keyAlias = System.getenv("OCTOPUS_SIGNING_KEY_ALIAS") ?: error("Missing signing key alias")
            keyPassword = System.getenv("OCTOPUS_SIGNING_KEY_PASSWORD") ?: error("Missing signing key password")
        }
    }
    buildTypes {
        debug {
            if (!previewStoreFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("stablePreview")
            if (providers.gradleProperty("octopusLiveBridge").orNull == "true") versionNameSuffix = "-device-tools"
        }
        release { isMinifyEnabled = false }
    }
    flavorDimensions += "purpose"
    productFlavors {
        create("diagnostic") {
            dimension = "purpose"
            versionNameSuffix = "-diagnostic"
            testInstrumentationRunner = "app.octopus.local.DeviceProbe"
        }
        create("demo") {
            dimension = "purpose"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    // Explicit local device tooling only. Never part of the app APK or ordinary CI UI tests.
    if (providers.gradleProperty("octopusLiveProbe").orNull == "true") {
        sourceSets.getByName("androidTest").java.srcDir(rootProject.file("tools/live-device-probe"))
    }
    if (providers.gradleProperty("octopusLiveBridge").orNull == "true") {
        sourceSets.getByName("debug").java.srcDir(rootProject.file("tools/live-device-bridge"))
        sourceSets.getByName("debug").manifest.srcFile(rootProject.file("tools/live-device-bridge/AndroidManifest.xml"))
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
