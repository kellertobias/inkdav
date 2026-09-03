plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "de.tobisk.inkdav"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.tobisk.inkdav"
        minSdk = 26
        targetSdk = 36
        versionCode = 1003005
        versionName = "1.3.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    val releaseKeystore = System.getenv("INKDAV_KEYSTORE_FILE")
    val releaseStorePassword = System.getenv("INKDAV_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("INKDAV_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("INKDAV_KEY_PASSWORD")
    val releaseStoreType = System.getenv("INKDAV_KEYSTORE_TYPE") ?: "JKS"
    val hasReleaseSigning = listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(releaseKeystore))
            storeType = releaseStoreType
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging.resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    val serializationBom = platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1")
    implementation(composeBom)
    implementation(serializationBom)
    androidTestImplementation(composeBom)
    androidTestImplementation(serializationBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.mnode.ical4j:ical4j:4.3.0")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.1.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
