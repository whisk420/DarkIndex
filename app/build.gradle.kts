plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.whisk.hackexlogger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whisk.hackexlogger"
        minSdk = 26
        targetSdk = 35
        val envVersionName = (project.findProperty("versionName") as? String)
            ?: System.getenv("VERSION_NAME")
            ?: "1.3-dev"

        val envVersionCode = (project.findProperty("versionCode") as? String
            ?: System.getenv("VERSION_CODE"))?.toIntOrNull()
            ?: 2

        versionCode = envVersionCode
        versionName = envVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
