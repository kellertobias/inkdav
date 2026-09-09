plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "de.tobisk.inkvault"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.tobisk.inkvault"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("inkVaultVersionCode").getOrElse("1004000").toInt()
        versionName = providers.gradleProperty("inkVaultVersion").getOrElse("1.4.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
    val store = System.getenv("INKVAULT_KEYSTORE_FILE")
    if (!store.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(store)
            storePassword = System.getenv("INKVAULT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("INKVAULT_KEY_ALIAS")
            keyPassword = System.getenv("INKVAULT_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!store.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:editor:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("de.sciss:jump3r:1.0.5")
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.5.3") {
        exclude(group = "com.android.support")
        exclude(group = "com.alibaba", module = "fastjson")
    }
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4.4") {
        exclude(group = "com.android.support")
        exclude(group = "com.alibaba", module = "fastjson")
    }
    implementation("com.alibaba:fastjson:2.0.21.android")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

tasks.register("verifyBooxDependencies") {
    group = "verification"
    description = "Verifies that InkVault resolves every required BOOX runtime dependency."

    doLast {
        val required =
            mapOf(
                "com.onyx.android.sdk:onyxsdk-device" to "1.3.5.3",
                "com.onyx.android.sdk:onyxsdk-pen" to "1.5.4.4",
                "org.lsposed.hiddenapibypass:hiddenapibypass" to "6.1"
            )
        listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").forEach { configurationName ->
            val resolved =
                configurations
                    .getByName(configurationName)
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .associate { "${it.moduleVersion.id.group}:${it.name}" to it.moduleVersion.id.version }
            val invalid = required.filter { (module, version) -> resolved[module] != version }
            check(invalid.isEmpty()) {
                "Missing or unexpected BOOX runtime dependencies in $configurationName: " +
                    invalid.entries.joinToString { (module, version) -> "$module:$version" }
            }
        }
    }
}
