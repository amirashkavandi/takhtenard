plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.takhtenard.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.takhtenard.game"
        minSdk = 24
        targetSdk = 35
        /* versionCode is what Android compares when it installs over an older build; versionName is
           only a label. The number the player sees in the menu comes from the asset file name, so a
           rebuild that changes only the wrapper gets an "-r" suffix instead of a new game version. */
        versionCode = 31
        versionName = "v30"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            /* Minifying a WebView shell gains nothing and risks the JavaScript bridge, whose method
               names must survive exactly as written. A store build still needs a signing config,
               which is a separate decision from this one. */
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /* Deprecated in Kotlin 2 in favour of the kotlin { compilerOptions { } } block, but still
       honoured; the warning on the first build is harmless. */
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    /* Empty on purpose. The app is one WebView plus two platform radios, so every library added here
       would be another thing to download on the first build and another thing that can break. */
}
