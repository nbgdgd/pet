import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * Реквизиты релизного ключа: сначала keystore.properties в корне проекта, иначе
 * переменные окружения (ANIBLAZE_STORE_FILE и компания) — так сборка одинаково
 * работает и на этой машине, и на любой другой, куда файл с паролями не поедет.
 *
 * Ничего не подставляется «по умолчанию»: пустая карта означает, что ключа нет, и
 * release-сборка обязана об этом сказать, а не подписаться отладочным ключом.
 */
val signingProps: Map<String, String> = run {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        val props = Properties().apply { file.inputStream().use { load(it) } }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .mapNotNull { key -> props.getProperty(key)?.takeIf { it.isNotBlank() }?.let { key to it } }
            .toMap()
    } else {
        listOf(
            "storeFile" to "ANIBLAZE_STORE_FILE",
            "storePassword" to "ANIBLAZE_STORE_PASSWORD",
            "keyAlias" to "ANIBLAZE_KEY_ALIAS",
            "keyPassword" to "ANIBLAZE_KEY_PASSWORD",
        ).mapNotNull { (key, env) -> System.getenv(env)?.takeIf { it.isNotBlank() }?.let { key to it } }
            .toMap()
    }
}

val releaseKeystore = signingProps["storeFile"]?.let { rootProject.file(it) }
val hasReleaseKey = releaseKeystore?.exists() == true && signingProps.size == 4

android {
    namespace = "com.aniblaze.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aniblaze.app"
        minSdk = 26
        targetSdk = 35
        // versionCode обязан только расти: Android отказывается ставить сборку с
        // номером меньше установленного, и никакая подпись этого не обходит.
        versionCode = 29
        versionName = "1.8.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingProps["storePassword"]
                keyAlias = signingProps["keyAlias"]
                keyPassword = signingProps["keyPassword"]
                // v1 (JAR) не нужен: minSdk 26, а схему v1 понимают только до Android 7.
                // Без неё APK меньше и устанавливается быстрее.
                enableV1Signing = false
                // v2 — то, чем подписаны уже разошедшиеся сборки; убирать нельзя.
                enableV2Signing = true
                // v3 добавляет в подпись «происхождение ключа». Сегодня она совпадает с
                // v2, но именно v3 позволяет когда-нибудь сменить ключ, не теряя
                // установленную базу. Без неё смена ключа невозможна в принципе.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Пусто, только если ключа нет вовсе, — и тогда сборка падает на проверке
            // ниже. Отладочный ключ здесь не подставляется никогда.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Отладочная сборка живёт под ДРУГИМ именем пакета. Иначе она занимает
            // место релизной на телефоне, и следующая релизная установка отлетает по
            // несовпадению подписи — вместе с избранным, историей и прогрессом.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    bundle {
        // В AAB Play нарезает ресурсы по языкам устройства. Интерфейс здесь русский
        // независимо от языка системы, поэтому нарезку выключаем: иначе на телефоне с
        // английской локалью часть строк приехала бы пустой.
        language { enableSplit = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

/**
 * Релиз без ключа собираться не должен. AGP в этом случае молча подписал бы APK
 * отладочным ключом — он ставится, выглядит рабочим и делает установку следующей
 * НАСТОЯЩЕЙ версии невозможной. Пусть лучше упадёт сборка.
 */
tasks.matching { it.name.contains("Release") && (it.name.startsWith("package") || it.name.startsWith("sign")) }
    .configureEach {
        doFirst {
            check(hasReleaseKey) {
                "Релизный ключ не найден. Положите keystore.properties в корень проекта " +
                    "(storeFile/storePassword/keyAlias/keyPassword) или задайте ANIBLAZE_STORE_FILE, " +
                    "ANIBLAZE_STORE_PASSWORD, ANIBLAZE_KEY_ALIAS, ANIBLAZE_KEY_PASSWORD."
            }
        }
    }

dependencies {
    implementation(project(":core-network"))
    implementation(project(":core-database"))
    implementation(project(":core-aggregator"))
    implementation(project(":core-torrent"))
    implementation(project(":core-player"))
    implementation(project(":core-ui"))
    implementation(project(":feature-home"))
    implementation(project(":feature-search"))
    implementation(project(":feature-detail"))
    implementation(project(":feature-player"))
    implementation(project(":feature-favorites"))
    implementation(project(":feature-history"))
    implementation(project(":feature-settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Ставит в ART профили, которые Compose и AndroidX кладут в свои AAR. Без этой
    // зависимости профили лежат в APK мёртвым грузом: класс-инициализатор, который
    // их применяет, живёт именно здесь. Разница видна на первом запуске и на первой
    // прокрутке после обновления — там, где иначе всё компилируется на лету.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.timber)
    // Нужен здесь ради ImageLoaderFactory в AniBlazeApp: настройки кэша задаются
    // приложением, а не каждым экраном.
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
