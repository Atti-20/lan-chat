// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

extra["minSdkVersion"] = 26
extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 37
