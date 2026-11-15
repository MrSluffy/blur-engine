/*
 * Copyright 2026 MrSluffy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.spotless)
}

// Apache 2.0 license headers. Templates are in spotless/.
//   ./gradlew spotlessApply   add missing headers to every source file
//   ./gradlew spotlessCheck   fail if any file lacks one (runs in CI)
// Only headers are managed here, no code formatting. $YEAR keeps the year already in a file.
spotless {
    val excluded = listOf("**/build/**", "**/.gradle/**", "spotless/**")
    kotlin {
        target("**/src/**/*.kt")
        targetExclude(excluded)
        licenseHeaderFile(rootProject.file("spotless/copyright.kt"))
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(excluded)
        // The header goes above the first top-level block of a build script.
        licenseHeaderFile(rootProject.file("spotless/copyright.kt"), "^(plugins|pluginManagement|import|rootProject|@file)")
    }
    format("xml") {
        target("**/src/**/*.xml")
        targetExclude(excluded)
        // Everything before the first element is replaced, so the template carries the <?xml?> line.
        licenseHeaderFile(rootProject.file("spotless/copyright.xml"), "<[a-zA-Z]")
    }
}

/** Modules published to Maven Central. Samples and benchmarks are not. */
val publishedModules = setOf(
    "blur-core", "blur-algorithms", "blur-animation", "blur-platform",
    "blur-render", "blur-capture", "blur-view", "blur-compose",
)

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

subprojects {
    if (name in publishedModules) {
        // Sources jar, Dokka javadoc jar, POM from gradle.properties, signing, Central Portal upload.
        apply(plugin = "com.vanniktech.maven.publish")
        apply(plugin = "org.jetbrains.dokka")
        // GitHub Packages, next to Maven Central: ./gradlew publishAllPublicationsToGitHubPackagesRepository
        // Credentials come from the GitHubPackagesUsername / GitHubPackagesPassword properties
        // (ORG_GRADLE_PROJECT_* in CI) and are only read when that task runs.
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/MrSluffy/blur-engine")
                    credentials(PasswordCredentials::class)
                }
            }
        }
    }
}

// The public API of every published module is recorded in <module>/api/*.api.
// `apiCheck` (part of `check`) fails when it changes; run `apiDump` to accept an intended change.
apiValidation {
    ignoredProjects += listOf("blur-samples", "blur-showcase", "benchmarks")
}

// Guard: never upload placeholder metadata to Maven Central or GitHub Packages.
gradle.taskGraph.whenReady {
    val uploading = allTasks.any {
        it.name.startsWith("publish") && (it.name.contains("MavenCentral") || it.name.contains("GitHubPackages"))
    }
    if (!uploading) return@whenReady
    val placeholders = listOf("POM_LICENSE_NAME", "POM_LICENSE_URL")
        .filter { providers.gradleProperty(it).orNull.let { v -> v == null || v.startsWith("TODO") } }
    check(placeholders.isEmpty()) {
        "Set ${placeholders.joinToString()} in gradle.properties before publishing."
    }
}
