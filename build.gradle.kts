plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    id("org.jetbrains.compose") version "1.7.0" apply false
}

/**
 * Отчёты компилятора Compose. Включаются флагом, потому что стоят времени сборки:
 *
 *     gradlew :app:assembleRelease -PcomposeMetrics=true
 *
 * В build/compose-reports каждого модуля появляются файлы `*-composables.txt`, где
 * про каждую @Composable написано `restartable skippable` или нет. НЕ пропускаемая
 * функция перерисовывается всякий раз, когда перерисовывается её родитель, даже если
 * её собственные аргументы не изменились, — это и есть та «просадка ниже 60», которую
 * на глаз видно, а по коду нет. Причина почти всегда одна: аргумент нестабильного
 * типа (обычно List).
 */
subprojects {
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            if (providers.gradleProperty("composeMetrics").orNull == "true") {
                metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
                reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
            }
            // Почему List объявлен стабильным — в самом файле.
            stabilityConfigurationFiles.add(
                rootProject.layout.projectDirectory.file("compose_stability.conf"),
            )
        }
    }
}
