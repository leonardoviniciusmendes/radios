import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun radioPttProperty(name: String): String =
    localProperties.getProperty(name) ?: error("$name must be defined in local.properties")

android {
    namespace = "com.example.radioptt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.radioptt"
        minSdk = 19
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("radiopttDebug") {
            storeFile = rootProject.file(radioPttProperty("RADIOPTT_KEYSTORE_PATH"))
            storePassword = radioPttProperty("RADIOPTT_KEYSTORE_PASSWORD")
            keyAlias = radioPttProperty("RADIOPTT_KEY_ALIAS")
            keyPassword = radioPttProperty("RADIOPTT_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("radiopttDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}
