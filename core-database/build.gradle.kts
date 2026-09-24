plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aniblaze.database"
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    implementation(libs.hilt.android)
    // Dagger 2.57's KSP validation crashes on Room Migration object fields.
    // This module has no Hilt ViewModels, so its compatible legacy processor
    // can keep producing the same AggregatedDeps consumed by the new app plugin.
    ksp("com.google.dagger:hilt-compiler:2.52")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
