plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.meshx.spike.desktop.MainKt"
        nativeDistributions {
            packageName = "MeshXComposeSpike"
            packageVersion = "0.1.0"
        }
    }
}
