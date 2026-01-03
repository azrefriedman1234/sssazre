import java.net.URL

plugins {
    id("com.android.application") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

android {
    namespace = "com.pasiflonet.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pasiflonet.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

val tdAarUrl = "https://jitpack.io/com/github/tdlibx/td/1.8.56/td-1.8.56.aar"
val ffmpegAarUrl = "https://artifactory.appodeal.com/appodeal-public/com/arthenica/ffmpeg-kit-full-gpl/6.0-2.LTS/ffmpeg-kit-full-gpl-6.0-2.LTS.aar"

val libsDir = project.layout.projectDirectory.dir("libs").asFile
val tdAarFile = File(libsDir, "td-1.8.56.aar")
val ffmpegAarFile = File(libsDir, "ffmpeg-kit-full-gpl-6.0-2.LTS.aar")

tasks.register("downloadAars") {
    doLast {
        libsDir.mkdirs()
        fun download(url: String, out: File) {
            if (out.exists() && out.length() > 1024 * 1024) return
            println("Downloading: $url -> ${out.absolutePath}")
            URL(url).openStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        download(tdAarUrl, tdAarFile)
        download(ffmpegAarUrl, ffmpegAarFile)
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadAars")
}

dependencies {
    // ML Kit (on-device translate)
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    implementation(files("libs/td-1.8.56.aar"))
    implementation(files("libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar"))

    implementation(files("libs/${tdAarFile.name}"))
    implementation(files("libs/${ffmpegAarFile.name}"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ML Kit (free, on-device models; no API key)
    // Image loading
    implementation("io.coil-kt:coil:2.6.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

