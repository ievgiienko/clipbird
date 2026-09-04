// RepositoriesMode.FAIL_ON_PROJECT_REPOS is @Incubating in Gradle 9.x.
@file:Suppress("UnstableApiUsage")

// Spelling out the plugin repositories costs one line and removes "where does
// this plugin id come from" as a question during review.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Safety net for the Java 25 toolchain in build.gradle.kts. Contributors run
// Windows, Linux and CI on different JDKs; without this a machine lacking 25
// fails with "no matching toolchains" instead of provisioning one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Repositories are declared here and nowhere else. A stray `repositories { }`
// in the build script becomes a build error rather than drifting in unnoticed.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "secure-clipboard"
