plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment (CI secrets, or exported locally).
// Without KEYSTORE_FILE the release APK is left unsigned: that is what F-Droid expects,
// since it builds and signs the app itself.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }

// CI run number for beta builds, so each beta has a higher versionCode than the previous one.
val betaBuild: Int? = System.getenv("BETA_BUILD")?.toIntOrNull()

android {
    namespace = "fr.priseok.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.priseok.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.0.0"
    }

    signingConfigs {
        if (keystoreFile != null && keystorePassword != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = "priseok"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Beta channel: the latest main, built by CI. Its own package, name and icon,
        // so it installs next to the stable app instead of replacing it.
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta" + (betaBuild?.let { ".$it" } ?: "")
            matchingFallbacks += listOf("release")
        }
    }

    // F-Droid requirement: no Google-signed dependency metadata blob in the APK
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            // Optional Maven mirror for Robolectric's Android jars: -ProbolectricRepo=<url>
            project.findProperty("robolectricRepo")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("beta")) { variant ->
        betaBuild?.let { code -> variant.outputs.forEach { it.versionCode.set(code) } }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Robolectric runs Android code on the computer; Roborazzi saves screenshots of Compose screens.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
