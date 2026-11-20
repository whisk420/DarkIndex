import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------------------------------------
// Signing: ONE permanent key for every build (Android Studio Run, local release, GitHub release),
// so any build installs over any other without an uninstall (and without losing data).
//
// Values come from (first match wins):
//   1. <project root>/keystore.properties  (local; git-ignored; see keystore.properties.example)
//   2. environment variables                (CI; set from GitHub Secrets)
//        SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD
// If neither is present, builds fall back to the machine's debug key (old behaviour) and Gradle
// prints a warning. CI refuses to publish in that state (see .github/workflows/android.yml).
// ---------------------------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties")).asText.orNull
    if (text != null) load(text.reader())
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: providers.environmentVariable(envKey).orNull)
        ?.trim()?.takeIf { it.isNotEmpty() }

val signingStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")
val signingStorePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
val hasDarkIndexSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { it != null }

if (!hasDarkIndexSigning) {
    logger.warn(
        "DarkIndex: no permanent signing key configured (keystore.properties / SIGNING_* env). " +
            "Falling back to the debug key — installs may require an uninstall. See keystore.properties.example."
    )
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

    signingConfigs {
        if (hasDarkIndexSigning) {
            create("darkindex") {
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Same key as releases, so Android Studio "Run" updates a GitHub-installed app in place.
            if (hasDarkIndexSigning) signingConfig = signingConfigs.getByName("darkindex")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasDarkIndexSigning) signingConfigs.getByName("darkindex") else signingConfigs.getByName("debug")
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
