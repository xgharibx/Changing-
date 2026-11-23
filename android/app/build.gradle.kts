plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes only from the environment (CI secret store). Never from the repository.
val uploadKeystore: String? = System.getenv("WADEH_UPLOAD_KEYSTORE")

android {
    namespace = "eg.wadeh.app"
    compileSdk = 36

    defaultConfig {
        // PROVISIONAL package id: confirm the publisher-controlled id before first Play upload (see docs/RELEASE.md).
        applicationId = "eg.wadeh.app"
        minSdk = 26 // Android 8.0+: adaptive icons, java.time; see PRODUCT.md decision D-07
        targetSdk = 36
        versionCode = (System.getenv("WADEH_VERSION_CODE") ?: "1").toInt()
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // 10.0.2.2 is the emulator's alias for the host machine.
            buildConfigField("String", "API_BASE_URL", "\"${System.getenv("WADEH_DEV_API") ?: "http://10.0.2.2:8080"}\"")
            buildConfigField("String", "ENV_NAME", "\"DEV\"")
            resValue("string", "app_name", "Wadeh DEV")
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "API_BASE_URL", "\"${System.getenv("WADEH_STAGING_API") ?: "https://staging-api.wadeh.invalid"}\"")
            buildConfigField("String", "ENV_NAME", "\"STAGING\"")
            resValue("string", "app_name", "Wadeh STAGING")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"${System.getenv("WADEH_PROD_API") ?: "https://api.wadeh.invalid"}\"")
            buildConfigField("String", "ENV_NAME", "\"\"")
            resValue("string", "app_name", "Wadeh")
        }
    }

    signingConfigs {
        if (uploadKeystore != null) create("upload") {
            storeFile = file(uploadKeystore)
            storePassword = System.getenv("WADEH_UPLOAD_STORE_PASSWORD")
            keyAlias = System.getenv("WADEH_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("WADEH_UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { generateLocaleConfig = true }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.graphicsMode", "NATIVE"); it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/"); it.systemProperty("screenshots.dir", rootProject.file("../evidence/screens").absolutePath); it.maxHeapSize = "3g" }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.uiautomator)
}
