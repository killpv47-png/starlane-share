plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "768m"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Benchmark results are written here so CI can publish them as an artifact.
    systemProperty("starlane.reportDir", layout.buildDirectory.dir("reports/starlane").get().asFile.absolutePath)
    // Large-file test size can be raised on CI (default keeps local runs quick).
    systemProperty("starlane.largeFileMb", System.getProperty("starlane.largeFileMb") ?: "64")
}
