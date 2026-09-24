plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aniblaze.aggregator"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        getByName("main").java.srcDir("../desktop-app/src/main/kotlin/com/aniblaze/aggregator/lampa")
    }
}

dependencies {
    implementation(project(":core-network"))
    implementation(project(":core-database"))
    implementation(libs.okhttp)

    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.rhino)
    implementation(libs.timber)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for unit tests (Android's is a throwing stub), so the
    // Kinogo playlist decoder can be tested on the JVM.
    testImplementation("org.json:json:20240303")
}
