plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.mebarlew.roxie"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mebarlew.roxie"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "GATEWAY_URL", "\"https://ai-gateway.mebarlew.workers.dev\"")
        buildConfigField("String", "GATEWAY_KEY", "\"${System.getenv("ROXIE_GATEWAY_KEY").orEmpty()}\"")
    }

    signingConfigs {
        create("roxie") {
            storeFile = file(System.getenv("ROXIE_KEYSTORE") ?: "roxie.p12")
            storePassword = System.getenv("ROXIE_KEYSTORE_PASSWORD")
            keyAlias = "roxie"
            keyPassword = System.getenv("ROXIE_KEYSTORE_PASSWORD")
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("roxie")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
