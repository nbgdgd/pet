import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.compose")
}

version = "1.0.2"

tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = project.version.toString()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // org.json (JVM build — Android's copy is a build-time stub) for the ported sources.
    implementation("org.json:json:20240303")
    // @Inject/@Singleton annotations on the ported sources (unused by any DI framework
    // here — desktop wiring is manual — but the classes must resolve to compile).
    implementation("javax.inject:javax.inject:1")

    // Poster loading (Compose Multiplatform build of Coil — separate artifact group
    // from the Android app's coil-compose 2.x, so no version conflict).
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // HLS/video playback backend (requires the free VLC media player installed system-wide).
    implementation("uk.co.caprica:vlcj:4.8.2")

    // Rhino JS engine — runs Lampa plugins (ES5) headlessly via a runtime shim so
    // their balancers can be used as sources ("add plugin by URL", like Lampa).
    implementation("org.mozilla:rhino:1.7.15")

    testImplementation(kotlin("test"))
}

// ---- Локальная установка ------------------------------------------------------
// Рабочая копия приложения живёт в %LOCALAPPDATA%\AniBlaze\app (ярлык — туда), а НЕ
// в build-папке: пересборка больше не сносит запущенное приложение и не падает на
// залоченных файлах. `gradlew :desktop-app:deployLocal` делает всё сам: собирает,
// подкладывает VLC, закрывает работающую копию, копирует и запускает заново.

val releaseAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app/AniBlaze")

// Кладёт libVLC из системной установки внутрь дистрибутива (<app>/vlc): на машине
// без установленного VLC плеер продолжает работать (VlcSupport подхватывает
// бандл через jna.library.path). Нет системного VLC при сборке — просто warning.
// jpackage отказывается писать в каталог, где уже лежит прошлый выхлоп ("New files
// were found") — чистим его перед сборкой. Если кто-то запустил приложение ПРЯМО из
// build-папки (старый ярлык/закреп), его файлы залочены — такие процессы, и только
// такие, закрываются здесь; установленная копия в LOCALAPPDATA не трогается.
val cleanReleaseDir = tasks.register("cleanReleaseDir") {
    doLast {
        val dir = layout.buildDirectory.dir("compose/binaries/main-release").get().asFile
        ProcessHandle.allProcesses()
            .filter { p ->
                p.info().command().map { it.startsWith(dir.absolutePath, ignoreCase = true) }.orElse(false)
            }
            .forEach { p ->
                logger.lifecycle("cleanReleaseDir: закрываю запущенную из build-папки копию (pid=${p.pid()})")
                p.destroyForcibly()
            }
        Thread.sleep(1200)
        dir.deleteRecursively()
    }
}
tasks.matching { it.name == "createReleaseDistributable" }.configureEach { dependsOn(cleanReleaseDir) }

val bundleVlc = tasks.register("bundleVlc") {
    dependsOn("createReleaseDistributable")
    doLast {
        // На этой машине VLC установлен в C:\Games; полагаться только на стандартный
        // Program Files означало незаметно выпускать сборку без видеодвижка.
        val vlcHome = listOf(
            File("C:/Program Files/VideoLAN/VLC"),
            File("C:/Program Files (x86)/VideoLAN/VLC"),
            File("C:/Games/VideoLAN/VLC"),
        ).firstOrNull { File(it, "libvlc.dll").isFile && File(it, "plugins").isDirectory }
        if (vlcHome == null) {
            logger.warn("bundleVlc: VLC не найден — дистрибутив без бандла (нужен системный VLC).")
            return@doLast
        }
        copy {
            from(vlcHome) {
                // vlc-cache-gen: regenerates plugins.dat for the DEPLOYED path.
                // The copied cache is keyed to Program Files and libVLC rejects it,
                // rescanning ~100 DLLs on every init (the 17s freeze).
                include("libvlc.dll", "libvlccore.dll", "vlc-cache-gen.exe", "plugins/**")
            }
            into(releaseAppDir.get().asFile.resolve("vlc"))
        }
    }
}

tasks.register("deployLocal") {
    dependsOn(bundleVlc)
    doLast {
        val target = File(System.getenv("LOCALAPPDATA") ?: error("нет LOCALAPPDATA"), "AniBlaze/app")
        // Never touch the working install until jpackage has actually produced a
        // complete replacement. `deployLocal -x bundleVlc` used to skip the
        // transitive package task, copy an empty directory and only then delete the
        // old install, leaving no AniBlaze.exe to start.
        val source = releaseAppDir.get().asFile
        require(File(source, "AniBlaze.exe").isFile) {
            "deployLocal: release app is missing at $source; run without excluding bundleVlc"
        }
        // Закрыть работающую копию — иначе её файлы залочены. Не запущена — не страшно.
        runCatching { ProcessBuilder("taskkill", "/IM", "AniBlaze.exe", "/F").start().waitFor() }
        Thread.sleep(1500)
        target.deleteRecursively()
        copy {
            from(source)
            into(target)
        }
        require(File(target, "AniBlaze.exe").isFile) {
            "deployLocal: copy completed without AniBlaze.exe at $target"
        }
        // Иконка для ярлыков живёт НАД app\ (деплой её не сносит): ярлык с
        // IconLocation на путь внутри app\ белел каждый раз, пока папка пересоздаётся.
        copy {
            from(file("icons/aniblaze.ico"))
            into(target.parentFile)
        }
        // Перегенерировать кеш плагинов libVLC под УСТАНОВЛЕННЫЙ путь — без него
        // каждый init libVLC на машине без системного VLC сканирует всё заново.
        val cacheGen = File(target, "vlc/vlc-cache-gen.exe")
        val plugins = File(target, "vlc/plugins")
        if (cacheGen.exists() && plugins.exists()) {
            runCatching { ProcessBuilder(cacheGen.absolutePath, plugins.absolutePath).start().waitFor() }
                .onFailure { logger.warn("vlc-cache-gen failed: $it") }
        }
        // Build-выхлоп после установки удаляется: старый ярлык/закреп, указывающий в
        // build-папку, сломается ГРОМКО вместо того, чтобы тихо запускать старую
        // сборку (ровно так пользователь и оказался на версии без фиксов).
        releaseAppDir.get().asFile.parentFile.deleteRecursively()
        // И сразу поднять свежую копию.
        ProcessBuilder(File(target, "AniBlaze.exe").absolutePath).start()
        logger.lifecycle("deployLocal: установлено в $target и запущено.")
    }
}

compose.desktop {
    application {
        mainClass = "com.aniblaze.desktop.MainKt"
        // Packaging shells out to jpackage from the JVM running Gradle, NOT from the
        // Kotlin toolchain. When Gradle runs on a JRE-style JDK (the JetBrains Runtime
        // this box has) that binary doesn't exist and the build dies at :checkRuntime.
        // Point it at the same JDK 17 the toolchain resolves — that one always has it.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }.get().metadata.installationPath.asFile.absolutePath
        // jdk-17 defaults to the platform charset (cp1251 on RU Windows), so libVLC's
        // UTF-8 track names (озвучки) came back as mojibake — force UTF-8 everywhere,
        // including JNA (which vlcj uses to read the native strings).
        jvmArgs += listOf(
            "-Daniblaze.buildId=${project.version}",
            "-Dfile.encoding=UTF-8",
            "-Dsun.jnu.encoding=UTF-8",
            "-Djna.encoding=UTF-8",
            // VLC owns every video repaint. Do not let AWT erase the heavyweight
            // Canvas with its platform fallback color during a window resize.
            "-Dsun.awt.noerasebackground=true",
        )
        // Bound the heap so the JVM's virtual-space reservation can't collide with
        // libVLC/Skia native allocations (the OOM "mmap failed for G1 virtual space"
        // crash during playback), and cap metaspace. G1 returns freed memory to the OS.
        jvmArgs += listOf(
            "-Xms128m",
            "-Xmx768m",
            "-XX:MaxMetaspaceSize=256m",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=100",
            "-XX:+HeapDumpOnOutOfMemoryError",
            // Падения в нативном коде (skiko, libVLC) JVM описывает в hs_err_pid*.log и
            // кладёт его в ТЕКУЩУЮ папку процесса — у ярлыка она произвольная, и файл
            // терялся. $APPDIR подставляет jpackage-лаунчер; %p — pid.
            "-XX:ErrorFile=\$APPDIR\\hs_err_%p.log",
        )
        // ProGuard chokes on vlcj/okhttp reflective classes and only shaves a few
        // MB off a JRE-bundled app anyway — not worth it for a personal build.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "AniBlaze"
            packageVersion = project.version.toString()
            // Ship the FULL JRE. jlink's minimal image strips modules that vlcj/JNA
            // need for the native video callback — without them the packaged app
            // decoded audio but never fired the video render callback (black + sound),
            // even though `gradlew run` (full JDK) rendered fine.
            includeAllModules = true
            windows {
                menuGroup = "AniBlaze"
                // Multi-size (16–256px) program icon for the packaged .exe, Start
                // menu and taskbar. Generated to match the in-app AniBlazeWindowIcon.
                iconFile.set(project.file("icons/aniblaze.ico"))
                shortcut = true
            }
        }
    }
}

// Свойства вида -Denhance.dump=<папка> должны доезжать до JVM, в которой идут тесты:
// сам Gradle их туда не передаёт, а стенд резкости умеет по такому свойству сохранять
// кадры «до и после» картинками (см. VideoEnhanceBenchTest.dump).
tasks.withType<Test>().configureEach {
    // Параллельные test executors уже исчерпывали Windows commit и создавали пачку
    // hs_err. Один fork оставляет место нативным Skia/VLC тестам и приложению.
    maxParallelForks = 1
    maxHeapSize = "512m"
    listOf("enhance.dump").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

/**
 * Живая проверка воспроизведения — ОТДЕЛЬНОЙ задачей и в СВОЕЙ JVM.
 *
 * Она поднимает настоящую libVLC и играет настоящий поток. Сами проверки проходят, но
 * нативный банк модулей не переживает завершения тестовой JVM: `Process 'Gradle Test
 * Executor' finished with non-zero exit value -1073741819` (0xC0000005, нарушение
 * доступа) — и падал ВЕСЬ прогон, хотя ни одно утверждение не нарушено. Держать из-за
 * этого красным набор из четырёх сотен тестов незачем.
 *
 *     gradlew :desktop-app:livePlayback
 *
 * Печатает разбор Anixart, вердикты проверки адресов, число декодированных кадров.
 */
val livePlayback by tasks.registering(Test::class) {
    group = "verification"
    description = "Anixart: разбор → проверка адресов → настоящие кадры в libVLC"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/AnixartPlaybackLiveTest*")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
    // Куча зажата НАМЕРЕННО. libVLC берёт память мимо кучи, а рядом обычно работают и
    // само приложение, и демон Gradle: с настройками по умолчанию JVM запрашивала
    // полгигабайта разом и падала — «Native memory allocation (mmap) failed to map
    // 536870912 bytes», а прогон обрывался кодом -1073741819. Проверке столько не нужно.
    minHeapSize = "128m"
    maxHeapSize = "512m"
}

val liveEpisodeTransition by tasks.registering(Test::class) {
    group = "verification"
    description = "Offscreen Compose + real libVLC: episode switch must not inherit the old clock"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/EpisodeSwitchPlaybackLiveTest*")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
    minHeapSize = "128m"
    maxHeapSize = "512m"
}

/**
 * Сетевые проверки (*LiveTest, *SmokeTest) ходят к настоящим источникам и зависят от
 * их состояния: сегодня Lampa не отдаёт «Дом дракона», завтра — Shikimori лежит.
 * Из обычного `test` они исключены — он про код, и должен быть зелёным без сети:
 *
 *     gradlew :desktop-app:test        — только автономные тесты
 *     gradlew :desktop-app:liveTest    — только сетевые (отчёт о здоровье источников)
 */
tasks.test { exclude("**/*LiveTest*", "**/*SmokeTest*") }

val liveTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Сетевые проверки источников: *LiveTest и *SmokeTest (без libVLC)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/*LiveTest*", "**/*SmokeTest*")
    exclude("**/AnixartPlaybackLiveTest*", "**/EpisodeSwitchPlaybackLiveTest*")
    outputs.upToDateWhen { false }
    minHeapSize = "128m"
    maxHeapSize = "512m"
}
