pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// Resolves (and downloads) the JDK the modules' `jvmToolchain(...)` asks for. Without
// it the build only works on a machine that already has exactly that JDK installed —
// this box has JetBrains Runtime 21 and nothing else, and Gradle failed outright with
// "No locally installed toolchains match" instead of fetching one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://dl.frostwire.com/maven")
            content { includeGroup("com.frostwire") }
        }
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "AniBlaze"

include(":app")
include(":desktop-app")

include(":core-network")
include(":core-database")
include(":core-player")
include(":core-aggregator")
include(":core-torrent")
include(":core-ui")
include(":feature-home")
include(":feature-search")
include(":feature-detail")
include(":feature-player")
include(":feature-favorites")
include(":feature-history")
include(":feature-settings")
