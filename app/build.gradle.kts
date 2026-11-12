import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Clé de distribution (keystore.properties, jamais versionnée). Utilisée seulement avec
// « ./gradlew assembleRelease -Pdistribution » ; sinon la clé de développement est gardée,
// pour pouvoir mettre à jour l'app déjà installée sans la désinstaller.
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val useDistributionKey = project.hasProperty("distribution") && keyProps.getProperty("storeFile") != null

// Version : année.mois.numéro (ex. 2026.11.1). Le versionCode en découle : 2026.11.1 -> 202611001.
val appVersion = "2026.11.8"
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (y, m, n) -> y * 100_000 + m * 1_000 + n }

android {
    namespace = "com.homepanel.kiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tilehouse.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        create("distribution") {
            if (keyProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (useDistributionKey) "distribution" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    // Tests sur l'ordinateur (sans tablette) : les classes Android non simulées renvoient une valeur par défaut.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Lecture du flux HLS des caméras (chargé seulement à l'ouverture du live)
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.10.0")

    // Tests : JUnit, et les vraies bibliothèques JSON et XML (celles d'Android ne tournent pas sur l'ordinateur).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
