plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Published through JitPack so modules can live in their own repos:
//   maven("https://jitpack.io") + implementation("com.github.ajitpal.dynamic-island-android:module-sdk:v1.9.0")
group = "com.github.ajitpal.dynamic-island-android"
version = "2.2.0"

android {
    namespace = "com.ajit.dynamicisland.sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { aidl = true }
    publishing { singleVariant("release") { withSourcesJar() } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "module-sdk"
            }
        }
    }
}
