import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.versions)
}

group = "com.secureclipboard"
version = "1.0.0"
description = "Secure Online Clipboard"

extra["tomcat.version"] = libs.versions.tomcat.get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)

    implementation(libs.spring.boot.flyway)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)

    implementation(libs.telegrambots.spring.boot.starter)

    runtimeOnly(libs.mysql.connector) {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.h2)
    testImplementation(libs.playwright)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Unit tests only. Browser tests need Playwright browsers installed and take minutes,
// so they live in a separate task instead of every `gradlew test`.
tasks.test {
    useJUnitPlatform()
    filter { excludeTestsMatching("com.secureclipboard.e2e.*") }
}

// Downloads the browser bundles the e2e tests drive. Needed once per machine
// and after every Playwright version bump.
tasks.register<JavaExec>("installPlaywrightBrowsers") {
    group = "verification"
    description = "Downloads Playwright browser bundles."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
    args("install")
    // CI runners lack the system packages the bundles link against, and there
    // --with-deps is allowed to install them. Developer machines close the same
    // gap without root through scripts/setup-e2e.sh.
    if (project.hasProperty("playwrightWithDeps")) {
        args("--with-deps")
    }
}

val e2eTest = tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Runs Playwright browser tests."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.secureclipboard.e2e.*") }
    shouldRunAfter(tasks.test)
}

// dependencyUpdates otherwise reports milestones and release candidates as
// upgrades, which for Spring means being told to move onto 4.2.0-M1. Only
// stable versions count as candidates.
fun isStable(version: String): Boolean {
    val marker = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    return marker || Regex("^[0-9,.v-]+(-r)?$").matches(version)
}

tasks.withType<DependencyUpdatesTask>().configureEach {
    rejectVersionIf { !isStable(candidate.version) }
}
