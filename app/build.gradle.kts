plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.stulluk.simpleplayer"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.stulluk.simpleplayer"
    minSdk = 29
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  buildFeatures {
    viewBinding = true
  }

  dependenciesInfo {
    // Keep APK metadata reproducible/clean for open-source distribution.
    includeInApk = false
    includeInBundle = false
  }
}

dependencies {
  val media3Version = "1.5.1"
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation("androidx.media3:media3-exoplayer:$media3Version")
  implementation("androidx.media3:media3-ui:$media3Version")
}
