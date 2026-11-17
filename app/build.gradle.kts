plugins {
    id("com.android.application")
}

// Build number comes from CI so each release has a higher versionCode than the last.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystoreFile = rootProject.file("app/keystore.jks")

android {
    namespace = "com.fixmylife.mobiletrainerlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fixmylife.mobiletrainerlink"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("shared") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "mobiletrainer"
                keyAlias = "mtlink"
                keyPassword = "mobiletrainer"
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
