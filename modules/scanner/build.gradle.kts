import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.ajit.dynamicisland.module.scanner"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ajit.dynamicisland.module.scanner"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { t ->
            t.systemProperty("screenshots.dir", rootProject.file("docs/screenshots/app").absolutePath)
            when (project.findProperty("screenshots")) {
                "record" -> t.systemProperty("roborazzi.test.record", "true")
                "verify" -> t.systemProperty("roborazzi.test.verify", "true")
                else -> t.exclude("**/ScreenshotTest*")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":module-sdk"))
    // Camera without Google Play services (Apache 2.0).
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // QR and barcode decoding on the phone (Apache 2.0), instead of ML Kit.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
