plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation("org.ow2.asm:asm:9.8")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        register("frameWaiterProbe") {
            id = "clma.frame-waiter-probe"
            implementationClass = "FrameWaiterProbePlugin"
        }
        register("androidLibrary") {
            id = "rikkahub.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "rikkahub.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
    }
}
