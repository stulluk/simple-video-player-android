plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.drejo.androidvideoplayer"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.drejo.androidvideoplayer"
    minSdk = 29
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
  }

  // Two flavors share the same code; the "fdroid" flavor adds the
  // MANAGE_EXTERNAL_STORAGE permission via its dedicated manifest fragment.
  flavorDimensions += "distribution"
  productFlavors {
    create("play") {
      dimension = "distribution"
    }
    create("fdroid") {
      dimension = "distribution"
      // Different applicationId so users can keep both variants installed
      // side by side; useful while we wait for the Play review.
      applicationIdSuffix = ".fdroid"
      versionNameSuffix = "-fdroid"
    }
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
    buildConfig = true
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
