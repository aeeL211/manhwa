import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.spotless)
}

android {
  namespace = "com.shinigami.client"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.shinigami.client"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0-github"

    androidResources {
      localeFilters.addAll(listOf("en", "in"))
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  // pengganti kotlinOptions untuk agp 9.0+
  kotlin {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_21)
    }
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
    compose = true
  }
}

spotless {
  kotlin {
    target("**/*.kt")
    ktlint("1.5.0")
      .editorConfigOverride(
        mapOf(
          "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
          "max_line_length" to "off"
        )
      )
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.fragment.ktx)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.androidx.startup)

  implementation(libs.androidx.swiperefreshlayout)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.material)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
}
