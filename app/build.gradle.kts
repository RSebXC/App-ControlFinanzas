plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "gt.finanzas.privadas"
    compileSdk = 35

    defaultConfig {
        applicationId = "gt.finanzas.privadas"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.0"
        testInstrumentationRunner = "gt.finanzas.privadas.UpgradeSmokeTest"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("test").java.srcDir("../tests")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
