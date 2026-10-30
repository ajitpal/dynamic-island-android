plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    // API reference for the website: ./gradlew :module-sdk:dokkaGeneratePublicationHtml → build/dokka/html
    id("org.jetbrains.dokka") version "2.2.0"
}

// Published through JitPack so modules can live in their own repos:
//   maven("https://jitpack.io") + implementation("com.github.ajitpal.dynamic-island-android:module-sdk:v2.0.0")
group = "com.github.ajitpal.dynamic-island-android"
version = "2.3.0"

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
    testImplementation("org.json:json:20240303")
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

dokka {
    moduleName.set("Dynamic Island module SDK")
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl("https://github.com/ajitpal/dynamic-island-android/tree/main/module-sdk/src/main/java")
        }
    }
}
