plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.mobilemanager"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.mobilemanager"
        minSdk = 33
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Phase 4.0: R8 verkleinert und optimiert (ohne Verschleierung, siehe proguard-rules.pro).
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

    packaging {
        resources {
            // Jakarta Mail / Activation bringen identische Lizenzdateien mit
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        // @ApplicationContext & Co. auf Konstruktor-Properties: neues Kotlin-2.x-Verhalten vorwegnehmen
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

// Room-Schemas für den MigrationTestHelper in die Test-Assets aufnehmen.
android.sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Phase 3: Custom Launcher als eigenständiges Feature-Modul
    implementation(project(":feature-launcher"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.mlkit.face.detection)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)

    // Phase 2: Alarm-Versand & Standort
    implementation(libs.okhttp)
    implementation(libs.angus.mail)
    implementation(libs.play.services.location)

    // Phase 4.8: EXIF-Entferner im Teilen-Menü
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Phase 4.0: Instrumentation-Tests (Room-Migrationen, Compose-UI)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
