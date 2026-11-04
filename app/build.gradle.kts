import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Secrets live in local.properties (git-ignored): tmdb.key=YOUR_KEY
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.anees.novamobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anees.novamobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TRAKT_ID", "\"${localProps.getProperty("trakt.clientId", "")}\"")
        buildConfigField("String", "TRAKT_SECRET", "\"${localProps.getProperty("trakt.clientSecret", "")}\"")
        buildConfigField("String", "ANILIST_ID", "\"${localProps.getProperty("anilist.clientId", "")}\"")
        buildConfigField("String", "MAL_ID", "\"${localProps.getProperty("mal.clientId", "")}\"")
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("tmdb.key", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key as the debug builds already on the TV, so it updates in place and keeps history / My List
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        // False positive with Kotlin smart casts on LiveData
        disable += "NullSafeMutableLiveData"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // HTML parsing for search-based sources (Vegamovies port)
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("io.coil-kt:coil:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.google.code.gson:gson:2.10.1")
}
