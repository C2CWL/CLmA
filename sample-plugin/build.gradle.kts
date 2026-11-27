plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.clma.plugin.sample.hello"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.clma.plugin.sample.hello"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":plugin-api"))
}
