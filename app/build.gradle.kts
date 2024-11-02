import com.android.Version
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true  // Hier hinzufügen
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    namespace = "info.meuse24.smsforwarderneo"
    compileSdk = 34

    defaultConfig {
        applicationId = "info.meuse24.smsforwarderneo"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "Anchovy"

        val agpVersion = Version.ANDROID_GRADLE_PLUGIN_VERSION
        buildConfigField("String", "AGP_VERSION", "\"$agpVersion\"")
        buildConfigField("String", "KOTLIN_VERSION", "\"${KotlinCompilerVersion.VERSION}\"")

        // Compose Version aus den Abhängigkeiten
        val composeVersion = project.configurations
            .findByName("implementation")
            ?.dependencies
            ?.find { it.group == "androidx.compose.runtime" && it.name == "runtime" }
            ?.version ?: "unknown"
        buildConfigField("String", "COMPOSE_VERSION", "\"$composeVersion\"")

        // Neue Build Config Fields

        buildTypes {
            debug {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                buildConfigField("String", "BUILD_TIME", "\"${sdf.format(Date())}\"")
                buildConfigField("String", "GRADLE_VERSION", "\"${gradle.gradleVersion}\"")
                buildConfigField("String", "BUILD_TYPE", "\"debug\"")
            }
            release {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                buildConfigField("String", "BUILD_TIME", "\"${sdf.format(Date())}\"")
                buildConfigField("String", "GRADLE_VERSION", "\"${gradle.gradleVersion}\"")
                buildConfigField("String", "BUILD_TYPE", "\"release\"")
            }
        }

        buildConfigField("String", "JDK_VERSION", "\"${System.getProperty("java.version")}\"")
        buildConfigField("String", "BUILD_TOOLS_VERSION", "\"${android.buildToolsVersion}\"")
        buildConfigField("String", "CMAKE_VERSION", "\"${project.findProperty("cmake.version") ?: "not used"}\"")
        buildConfigField("String", "NDK_VERSION", "\"${project.findProperty("android.ndkVersion") ?: "not used"}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.common)
    implementation(libs.androidx.security.crypto)
    implementation(libs.libphonenumber)
    implementation(libs.androidx.espresso.core)
    implementation(libs.material)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)


}


