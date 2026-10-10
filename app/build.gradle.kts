plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fanqie.lico"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fanqie.lico"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/xiaofanqie.jks")
            storePassword = "xiaofanqie"
            keyAlias = "xiaofanqie"
            keyPassword = "xiaofanqie"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 使用同一套签名，方便直接覆盖安装测试
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        // 必须解压原生库：后端可执行文件以 lib*.so 形式打包，需被释放到 nativeLibraryDir 才有执行权限
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}