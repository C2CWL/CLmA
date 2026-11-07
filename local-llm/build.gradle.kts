import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.clma.locallm"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Return default values (null / 0 / false) for Android framework calls in JVM unit
        // tests instead of throwing "not mocked" exceptions. Required because production
        // code (e.g. LlamaCpp runtime) calls android.util.Log which isn't available on JVM.
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("main").jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
    }
    packaging {
        jniLibs {
            // Avoid extracting native libs at install time so System.loadLibrary path stays cheap.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(project(":ai"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
