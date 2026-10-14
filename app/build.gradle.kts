import io.github.reactivecircus.appversioning.toSemVer
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.app.versioning)
    alias(libs.plugins.spotless)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kover)
    alias(libs.plugins.sonarqube)
}

appVersioning {
    tagFilter.set("v[0-9].*")
    overrideVersionCode { gitTag, _, _ ->
        val semVer = gitTag.toSemVer()
        semVer.major * 10000000 + semVer.minor * 100000 + semVer.patch * 1000 + gitTag.commitsSinceLatestTag
    }
    overrideVersionName { gitTag, _, _ ->
        if (gitTag.commitsSinceLatestTag != 0) {
            "git-${gitTag.rawTagName}-${gitTag.commitsSinceLatestTag}-g${gitTag.commitHash}"
        } else {
            gitTag.rawTagName
        }
    }
}

android {
    namespace = "org.connectbot"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "solutions.ormus.logos"

        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()

        vectorDrawables.useSupportLibrary = true

        testApplicationId = "org.connectbot.tests"
        testInstrumentationRunner = "org.connectbot.HiltTestRunner"

        // The following argument makes the Android Test Orchestrator run its
        // "pm clear" command after each test invocation. This command ensures
        // that the app's state is completely cleared between tests.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"

        multiDexEnabled = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    bundle {
        language {
            enableSplit = true
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (project.hasProperty("keystorePassword")) {
            create("release") {
                storeFile = file(property("keystoreFile") as String)
                storePassword = property("keystorePassword") as String
                keyAlias = property("keystoreAlias") as String
                keyPassword = property("keystorePassword") as String
            }
        }
    }

    buildTypes {
        release {
            manifestPlaceholders["memtagMode"] = "async"
            isShrinkResources = true
            isMinifyEnabled = true

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg")
            testProguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg", "proguard-tests.cfg")

            if (project.hasProperty("keystorePassword")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        debug {
            manifestPlaceholders["memtagMode"] = "sync"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg", "proguard-debug.cfg")
            testProguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg", "proguard-tests.cfg")

            applicationIdSuffix = ".debug"
            enableAndroidTestCoverage = true
        }
    }

    flavorDimensions.add("license")

    productFlavors {
        // This product flavor uses the Conscrypt library which is open
        // source and licensed under Apache 2.
        create("oss") {
            dimension = "license"
            versionNameSuffix = "-oss"
            // No Google Play Services available for downloadable fonts
            buildConfigField("Boolean", "HAS_DOWNLOADABLE_FONTS", "false")
        }
    }

    testOptions {
        execution = "ANDROID_TEST_ORCHESTRATOR"
        animationsDisabled = true
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("main") {
            assets.directories.add("build/generated/exportSchema")
        }
        getByName("test") {
            kotlin.directories.add("src/sharedTest/kotlin")
        }
        getByName("testOss") {
            kotlin.directories.add("src/sharedTest/kotlin")
        }
        getByName("androidTest") {
            kotlin.directories.add("src/sharedTest/kotlin")
        }
    }

    lint {
        abortOnError = false
        lintConfig = file("lint.xml")
        checkTestSources = false
    }

    packaging {
        resources.excludes.add("META-INF/LICENSE.txt")
        resources.excludes.add("LICENSE.txt")
        resources.excludes.add("**/*.gwt.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val sonarJavaBinaries = mutableListOf<Provider<String>>()
val sonarJavaTestBinaries = mutableListOf<Provider<String>>()
val sonarAndroidLintReportPaths = mutableListOf<Provider<String>>()

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val variantName = variant.name
        val variantTaskName = variantName.replaceFirstChar { it.uppercaseChar() }

        sonarJavaBinaries +=
            layout.buildDirectory
                .dir("intermediates/classes/$variantName/jacoco$variantTaskName/dirs")
                .map { it.asFile.path }
        sonarJavaBinaries +=
            layout.buildDirectory
                .dir("intermediates/javac/$variantName/compile${variantTaskName}JavaWithJavac/classes")
                .map { it.asFile.path }
        sonarJavaTestBinaries +=
            layout.buildDirectory
                .dir("intermediates/classes/${variantName}UnitTest/transform${variantTaskName}UnitTestClassesWithAsm/dirs")
                .map { it.asFile.path }
        sonarJavaTestBinaries +=
            layout.buildDirectory
                .dir("intermediates/javac/${variantName}AndroidTest/compile${variantTaskName}AndroidTestJavaWithJavac/classes")
                .map { it.asFile.path }
        sonarAndroidLintReportPaths +=
            layout.buildDirectory
                .file("reports/lint-results-$variantName.xml")
                .map { it.asFile.path }
    }
}

kover {
    reports {
        filters {
            excludes {
                // Third-party code vendored in the source tree
                packages(
                    "de.mud.*",
                    "com.google.ase",
                    "org.apache.*",
                    "org.keyczar.*",
                    "org.openintents.*",
                )
                // Hilt/Dagger generated code
                packages("dagger.*", "hilt_aggregated_deps")
                classes(
                    "*_MembersInjector",
                    "*_MembersInjector\$*",
                    "*_Factory",
                    "*_Factory\$*",
                    "*Hilt_*",
                    "*_GeneratedInjector",
                    "*_HiltModules*",
                    "*_HiltComponents*",
                    "*Module_Provide*",
                    "*Module_Bind*",
                )
                // Room generated implementations
                classes(
                    "*_Impl",
                    "*_Impl\$*",
                    "*_AutoMigration_*",
                    "*_AutoMigration_*\$*",
                )
                // Build config
                classes("*.BuildConfig")
            }
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "connectbot_connectbot")
        property("sonar.organization", "connectbot")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.exclusions",
            """
            |src/main/java/com/google/ase/**,
            |src/main/java/de/mud/**,
            |src/main/java/org/apache/**,
            |src/main/java/org/keyczar/**,
            |src/main/java/org/openintents/**
            """.trimMargin(),
        )
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            """
            |build/reports/kover/reportOssDebug.xml,
            |build/reports/kover/reportGoogleDebug.xml,
            |build/reports/coverage/androidTest/oss/debug/connected/report.xml,
            |build/reports/coverage/androidTest/google/debug/connected/report.xml,
            """.trimMargin(),
        )
    }
}

afterEvaluate {
    sonar {
        properties {
            property("sonar.java.binaries", sonarJavaBinaries.joinToString(",") { it.get() })
            property("sonar.java.test.binaries", sonarJavaTestBinaries.joinToString(",") { it.get() })
            property("sonar.androidLint.reportPaths", sonarAndroidLintReportPaths.joinToString(",") { it.get() })
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Generate filtered export schema from Room schema
// Only includes tables needed for export/import (profiles, hosts, port_forwards)
val generateExportSchema by tasks.registering {
    val exportTables = setOf("profiles", "hosts", "port_forwards")
    val excludedFields = setOf("last_connect", "host_key_algo")

    // Read schema version from Room's @Database annotation.
    val databaseFile = file("src/main/java/org/connectbot/data/ConnectBotDatabase.kt")
    val schemaVersion =
        databaseFile
            .readText()
            .let { Regex("""@Database\s*\([\s\S]*?version\s*=\s*(\d+)""").find(it) }
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("Could not find @Database version in $databaseFile")
    val inputFile = file("schemas/org.connectbot.data.ConnectBotDatabase/$schemaVersion.json")
    val outputDir = file("build/generated/exportSchema")
    val outputFile = file("$outputDir/export_schema.json")

    inputs.file(databaseFile)
    inputs.file(inputFile)
    outputs.file(outputFile)

    doLast {
        val inputJson = groovy.json.JsonSlurper().parseText(inputFile.readText()) as Map<*, *>
        val database = inputJson["database"] as Map<*, *>
        val entities = database["entities"] as List<*>

        // Filter entities to only include export tables
        val filteredEntities =
            entities
                .filter { entity ->
                    val entityMap = entity as Map<*, *>
                    entityMap["tableName"] in exportTables
                }.map { entity ->
                    val entityMap = (entity as Map<*, *>).toMutableMap()
                    // Mark excluded fields instead of removing them (needed for NOT NULL defaults)
                    val fields = entityMap["fields"] as List<*>
                    entityMap["fields"] =
                        fields.map { field ->
                            val fieldMap = (field as Map<*, *>).toMutableMap()
                            if (fieldMap["columnName"] in excludedFields) {
                                fieldMap["excluded"] = true
                            }
                            fieldMap
                        }
                    entityMap
                }

        val filteredSchema =
            mapOf(
                "formatVersion" to inputJson["formatVersion"],
                "database" to
                    mapOf(
                        "version" to database["version"],
                        "entities" to filteredEntities,
                    ),
            )

        outputDir.mkdirs()
        outputFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(filteredSchema)))
    }
}

// Ensure export schema is generated before tasks that read from the assets directory
tasks
    .matching {
        (it.name.contains("merge") && it.name.contains("Assets")) ||
            it.name.contains("Lint", ignoreCase = true)
    }.configureEach {
        dependsOn(generateExportSchema)
    }

dependencies {
    implementation(libs.sshlib)
    implementation(libs.termlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.navigation.testing)
    implementation(libs.androidx.ui)
    testImplementation(libs.play.feature.delivery)
    "ossImplementation"(libs.conscrypt.android)
    // UnifiedPush (Apache-2.0): push through the user's own distributor (the ntfy app); no Google services.
    implementation(libs.unifiedpush.connector)

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.appcompat.resources)
    implementation(libs.androidx.preference)
    implementation(libs.material)
    implementation(libs.timber)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)

    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)

    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.android.compiler)
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.espresso.contrib) {
        exclude(group = "com.google.android.apps.common.testing.accessibility.framework", module = "accessibility-test-framework")
    }
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.assertj.core)

    androidTestUtil(libs.androidx.test.orchestrator)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.assertj.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.androidx.room.testing)

    testCompileOnly(libs.conscrypt.openjdk.uber)
    testRuntimeOnly(libs.conscrypt.android)
    testImplementation(libs.conscrypt.openjdk.uber)
}

// Ormus brand tokens. app/brand/ormus-tokens.json is a vendored copy of
// ormus-brand/tokens.json (the brand's single source of truth). This task turns it
// into OrmusTokens.kt and res/values/ormus_tokens.xml so no brand hex is typed by
// hand. Refresh: copy the new tokens.json over the vendored file, run
// `./gradlew :app:generateOrmusTokens`, commit both outputs. OrmusTokensTest fails
// when the generated Kotlin drifts from the vendored JSON.
abstract class GenerateOrmusTokens : DefaultTask() {
    @get:InputFile
    abstract val tokens: RegularFileProperty

    @get:InputFile
    abstract val licenseHeader: RegularFileProperty

    @get:OutputFile
    abstract val kotlinOut: RegularFileProperty

    @get:OutputFile
    abstract val xmlOut: RegularFileProperty

    private fun argb(value: String): String {
        val v = value.trim()
        if (v.startsWith("#")) return "FF" + v.drop(1).uppercase()
        val parts = v.substringAfter("(").substringBefore(")").split(",").map { it.trim() }
        val alpha = Math.round(parts[3].toDouble() * 255).toInt()
        return "%02X%02X%02X%02X".format(alpha, parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }

    private fun pascal(key: String) = key.replaceFirstChar { it.uppercase() }

    private fun snake(key: String) = key.replace(Regex("([A-Z])"), "_$1").lowercase()

    private fun rem(value: String, base: Double = 16.0) = value.removeSuffix("rem").toDouble() * base

    private fun em(value: String) = if (value == "0") 0.0 else value.removeSuffix("em").toDouble()

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(tokens.get().asFile) as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        fun group(name: String) = (json[name] as Map<String, Any>).filterKeys { !it.startsWith("$") }
        val header = licenseHeader.get().asFile.readText().replace("\$YEAR", "2026")
        val kt = StringBuilder(header)
        kt.append("\n// Generated by :app:generateOrmusTokens from app/brand/ormus-tokens.json. Do not edit.\n\n")
        kt.append("package org.connectbot.ui.theme\n\n")
        kt.append("import androidx.compose.animation.core.CubicBezierEasing\n")
        kt.append("import androidx.compose.ui.graphics.Color\n\n")
        kt.append("/** Ormus brand tokens (ormus-brand tokens.json version ${json["version"]}). */\n")
        kt.append("object OrmusTokens {\n")
        kt.append("    const val VERSION = \"${json["version"]}\"\n\n")
        group("color").forEach { (k, v) -> kt.append("    val ${pascal(k)} = Color(0x${argb(v.toString())})\n") }
        listOf("canvas", "print").forEach { g ->
            kt.append("\n    object ${pascal(g)} {\n")
            group(g).forEach { (k, v) -> kt.append("        val ${pascal(k)} = Color(0x${argb(v.toString())})\n") }
            kt.append("    }\n")
        }
        val radius = json["radius"].toString().removeSuffix("px")
        kt.append("\n    /** The brand's single corner radius, in dp (CSS px). */\n")
        kt.append("    const val RADIUS_DP = ${radius}f\n")

        @Suppress("UNCHECKED_CAST")
        val ease = ((json["motion"] as Map<String, Any>)["easeRevealPoints"] as List<Number>).joinToString { "${it.toFloat()}f" }
        kt.append("\n    /** cubic-bezier reveal curve used for every transition. */\n")
        kt.append("    val EaseReveal = CubicBezierEasing($ease)\n")
        kt.append("\n    /** Brand type scale in sp (1rem = 16sp); line heights resolved to sp. */\n")
        kt.append("    object TypeScale {\n")
        group("typeScale").forEach { (k, v) ->
            @Suppress("UNCHECKED_CAST")
            val s = v as Map<String, Any>
            val size = rem(s["size"].toString())
            val lh = s["lineHeight"].toString().let { if (it.endsWith("rem")) rem(it) else it.toDouble() * size }
            kt.append(
                "        val ${pascal(k)} = OrmusTypeToken(font = \"${s["font"]}\", sizeSp = ${size.toFloat()}f, " +
                    "lineHeightSp = ${lh.toFloat()}f, weight = ${s["weight"]}, trackingEm = ${em(s["tracking"].toString()).toFloat()}f)\n",
            )
        }
        kt.append("    }\n}\n\n")
        kt.append("/** One brand type-scale entry; [font] names a family in tokens.json (display, sans, mono). */\n")
        kt.append("data class OrmusTypeToken(\n")
        kt.append("    val font: String,\n    val sizeSp: Float,\n    val lineHeightSp: Float,\n    val weight: Int,\n    val trackingEm: Float,\n)\n")
        kotlinOut.get().asFile.apply { parentFile.mkdirs() }.writeText(kt.toString())

        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        xml.append(
            "<!-- Generated by :app:generateOrmusTokens from app/brand/ormus-tokens.json. Do not edit. -->\n" +
                "<resources xmlns:tools=\"http://schemas.android.com/tools\" tools:ignore=\"UnusedResources\">\n",
        )
        group("color").forEach { (k, v) -> xml.append("\t<color name=\"ormus_${snake(k)}\">#${argb(v.toString())}</color>\n") }
        xml.append("</resources>\n")
        xmlOut.get().asFile.apply { parentFile.mkdirs() }.writeText(xml.toString())
    }
}

tasks.register<GenerateOrmusTokens>("generateOrmusTokens") {
    group = "ormus"
    description = "Generates OrmusTokens.kt and ormus_tokens.xml from the vendored brand tokens."
    tokens.set(layout.projectDirectory.file("brand/ormus-tokens.json"))
    licenseHeader.set(rootProject.layout.projectDirectory.file("spotless/license-header.txt"))
    kotlinOut.set(layout.projectDirectory.file("src/main/java/org/connectbot/ui/theme/OrmusTokens.kt"))
    xmlOut.set(layout.projectDirectory.file("src/main/res/values/ormus_tokens.xml"))
}
