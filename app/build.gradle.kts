import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.readiness.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readiness.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "4.0"
        vectorDrawables { useSupportLibrary = true }
    }

    /* Signatur: Der Schlüssel liegt NICHT im Repository.
       `local.properties` (per .gitignore ausgeschlossen) verweist mit
       `readiness.signing.file=<Pfad>\signing.properties` auf eine Datei außerhalb des Repos,
       in der CI übernimmt das die Umgebungsvariable READINESS_SIGNING_FILE. Die Datei enthält
       readiness.keystore, readiness.keystore.password, readiness.key.alias und
       readiness.key.password. Fehlt sie, wird mit dem Standard-Debug-Schlüssel des Rechners
       signiert — eine so gebaute APK lässt sich dann nicht über eine anders signierte
       installieren (Android verlangt erst die Deinstallation, dabei gehen API-Key,
       Einstellungen und Kraftdaten-Cache verloren). */
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val signingProps = Properties().apply {
        (localProps.getProperty("readiness.signing.file") ?: System.getenv("READINESS_SIGNING_FILE"))
            ?.let { File(it) }?.takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (signingProps.getProperty("readiness.keystore") != null) {
            create("shared") {
                storeFile = File(signingProps.getProperty("readiness.keystore"))
                storePassword = signingProps.getProperty("readiness.keystore.password")
                keyAlias = signingProps.getProperty("readiness.key.alias")
                keyPassword = signingProps.getProperty("readiness.key.password")
            }
        }
    }
    val appSigning = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = appSigning
        }
        release {
            // Minify bleibt aus: kotlinx.serialization bräuchte sonst gepflegte ProGuard-Regeln
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ohne signing.properties fehlt der Schlüssel: dann entsteht eine UNSIGNIERTE Release-APK
            signingConfig = signingConfigs.findByName("shared")
        }
        /* Demo: synthetische Daten statt intervals.icu, eigene Kennung. Installiert sich neben
           der echten App, ohne deren Daten zu berühren — für Screenshots und zum Ausprobieren
           ohne API-Key. */
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            matchingFallbacks += "debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
