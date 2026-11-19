plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zrec"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zrec"
        minSdk {
            version = release(29)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val storePath = findProperty("ZREC_KEYSTORE_PATH") as String?
                ?: System.getenv("ZREC_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = findProperty("ZREC_KEYSTORE_PASSWORD") as String?
                    ?: System.getenv("ZREC_KEYSTORE_PASSWORD")
                keyAlias = findProperty("ZREC_KEY_ALIAS") as String?
                    ?: System.getenv("ZREC_KEY_ALIAS")
                keyPassword = findProperty("ZREC_KEY_PASSWORD") as String?
                    ?: System.getenv("ZREC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            val storePath = findProperty("ZREC_KEYSTORE_PATH") as String?
                ?: System.getenv("ZREC_KEYSTORE_PATH")
            if (storePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
