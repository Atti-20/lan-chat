plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.meshx.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.meshx.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 30000
        versionName = "0.3.0"
        manifestPlaceholders["meshxCleartextTraffic"] = "false"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    flavorDimensions += "network"
    productFlavors {
        create("secure") {
            dimension = "network"
            manifestPlaceholders["meshxCleartextTraffic"] = "false"
        }
        create("lan") {
            dimension = "network"
            applicationIdSuffix = ".lan"
            versionNameSuffix = "-lan"
            manifestPlaceholders["meshxCleartextTraffic"] = "true"
        }
    }

    androidComponents {
        beforeVariants(selector().withBuildType("release")) { variantBuilder ->
            if (variantBuilder.productFlavors.any { it.second == "lan" }) {
                variantBuilder.enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val syncVueUi by tasks.registering(Exec::class) {
    group = "meshx"
    description = "Builds the shared Vue UI and syncs it into the Capacitor Android shell."
    workingDir = rootProject.projectDir
    commandLine("npm", "run", "sync:web")
    inputs.files(
        // Resolve from apps/android, not the :app subproject directory.
        // Otherwise Gradle watches the nonexistent apps/frontend and packages stale UI.
        fileTree(rootProject.file("../../frontend")) {
            include("src/**", "public/**", "index.html", "package*.json", "tsconfig*.json", "vite.config.*")
        },
        rootProject.file("package.json"),
        rootProject.file("package-lock.json"),
        file("../capacitor.config.json"),
        file("../scripts/sync-web.mjs"),
    )
    outputs.dir("src/main/assets/public")
    outputs.files(
        "src/main/assets/capacitor.config.json",
        "src/main/assets/capacitor.plugins.json",
    )
}

tasks.named("preBuild").configure {
    dependsOn(syncVueUi)
}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(project(":capacitor-android"))
    implementation(project(":capacitor-app"))
    implementation(project(":capacitor-local-notifications"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
