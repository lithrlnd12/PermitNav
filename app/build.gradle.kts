import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")  // Google Services plugin for Firebase
}

// Load HERE SDK credentials
val credentialsPropertiesFile = rootProject.file("credentials.properties")
val credentialsProperties = Properties()
if (credentialsPropertiesFile.exists()) {
    credentialsProperties.load(FileInputStream(credentialsPropertiesFile))
}

// Load local properties (including OpenAI key)
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.permitnav"
    compileSdk = 34
    
    signingConfigs {
        create("release") {
            storeFile = file("../clearway-cargo-release.keystore")
            storePassword = "your-password-here"  // Replace with your actual password
            keyAlias = "clearway-cargo"
            keyPassword = "your-password-here"  // Replace with your actual password
        }
    }

    defaultConfig {
        applicationId = "com.permitnav.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // HERE SDK credentials from credentials.properties
        buildConfigField("String", "HERE_ACCESS_KEY_ID", "\"${credentialsProperties.getProperty("accessKeyId") ?: ""}\"")
        buildConfigField("String", "HERE_ACCESS_KEY_SECRET", "\"${credentialsProperties.getProperty("accessKeySecret") ?: ""}\"")
        
        // Legacy HERE API credentials from local.properties (for REST API)
        buildConfigField("String", "HERE_API_KEY", "\"${project.findProperty("HERE_API_KEY")}\"")
        buildConfigField("String", "HERE_APP_ID", "\"${project.findProperty("HERE_APP_ID")}\"")
        
        // Google Maps API key from local.properties
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${localProperties.getProperty("MAPS_API_KEY") ?: ""}\"")
        
        // OpenAI API key for intelligent permit parsing (from local.properties)
        buildConfigField("String", "OPENAI_API_KEY", "\"${localProperties.getProperty("OPENAI_API_KEY") ?: ""}\"" )
        
        // Google Maps API Key from local.properties
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // HERE SDK dependency
    implementation(fileTree(mapOf(
        "dir" to "libs",
        "include" to listOf("*.aar", "*.jar"),
        "exclude" to listOf("*mock*.jar")
    )))
    
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    implementation("com.google.mlkit:text-recognition:16.0.0")
    
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    
    implementation("io.ktor:ktor-client-android:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-client-logging:2.3.7")
    
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:maps-compose:2.15.0")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")
    
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.json:json:20230618")
    
    // Firebase BOM - manages all Firebase library versions
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    
    // Firebase libraries (versions managed by BOM)
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-analytics") // Optional but recommended
    
    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    
    // WebRTC for OpenAI Realtime API voice chat
    implementation("com.mesibo.api:webrtc:1.0.5")
    
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}