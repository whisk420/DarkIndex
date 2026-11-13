pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Fix AGP AndroidLocationsException when multiple Android preference environment variables or system properties are set
if (System.getenv("ANDROID_USER_HOME") != null) {
    System.clearProperty("ANDROID_PREFS_ROOT")
    System.clearProperty("ANDROID_SDK_HOME")
    @Suppress("UNCHECKED_CAST")
    try {
        val peClass = Class.forName("java.lang.ProcessEnvironment")
        val envField = peClass.getDeclaredField("theEnvironment").apply { isAccessible = true }
        val envMap = envField.get(null) as? MutableMap<String, String>
        envMap?.remove("ANDROID_PREFS_ROOT")
        envMap?.remove("ANDROID_SDK_HOME")
        val ciEnvField = peClass.getDeclaredField("theCaseInsensitiveEnvironment").apply { isAccessible = true }
        val ciEnvMap = ciEnvField.get(null) as? MutableMap<String, String>
        ciEnvMap?.remove("ANDROID_PREFS_ROOT")
        ciEnvMap?.remove("ANDROID_SDK_HOME")
    } catch (_: Throwable) {}
}

rootProject.name = "DarkIndex"
include(":app")
