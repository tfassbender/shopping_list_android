plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

// --- API client generation -------------------------------------------------------------------
// The REST client and models are generated from doc/openapi.json (refresh it with the
// "update-openapi" skill). Never edit the generated code; it lives in build/.
val openApiSpec = rootProject.file("doc/openapi.json")
val openApiOutputDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(openApiSpec.absolutePath)
    outputDir.set(openApiOutputDir.get().asFile.absolutePath)
    packageName.set("com.tfassbender.shoppinglist.api")
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
            "dateLibrary" to "java8",
            "enumPropertyNaming" to "UPPERCASE",
            "omitGradleWrapper" to "true",
            "sourceFolder" to "src/main/kotlin",
        )
    )
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)
}

// --- Version -----------------------------------------------------------------------------------
// The only place to change for a release. versionCode is derived from it and must grow with every
// update: MAJOR * 10000 + MINOR * 100 + PATCH (0.1.0 -> 100, 0.1.1 -> 101).
val appVersion = "0.1.0"
val appVersionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "minor and patch must be < 100" }
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "com.tfassbender.shoppinglist"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.tfassbender.shoppinglist"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Production server - not deployed yet. Must be https:// (see CLAUDE.md, "Security").
        buildConfigField("String", "API_BASE_URL", "\"https://shopping-list.invalid/\"")
    }

    buildTypes {
        debug {
            // Separate app next to the real one: own ID, own name, own storage.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Local dev server (./gradlew :backend:quarkusDev in the server repo). 10.0.2.2 is the host
            // machine as seen from the emulator; on a real device use `adb reverse tcp:4723 tcp:4723`
            // and `-PdevApiBaseUrl=http://localhost:4723/`. Plain HTTP only works for these local hosts
            // (network security config + AuthInterceptor).
            val devApiBaseUrl = providers.gradleProperty("devApiBaseUrl").getOrElse("http://10.0.2.2:4723/")
            buildConfigField("String", "API_BASE_URL", "\"$devApiBaseUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release signing: decided when the app is released (see doc/implementation_plan.md).
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    bundle {
        // The language can be switched in the app (user setting), so every bundle must contain all languages.
        language { enableSplit = false }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["main"].kotlin.directories.add(
        openApiOutputDir.get().dir("src/main/kotlin").asFile.absolutePath
    )
    testOptions {
        // Plain JVM unit tests may touch android.* classes (e.g. Log) without crashing.
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild") { dependsOn(tasks.named("openApiGenerate")) }

// Integration tests against another server: ./gradlew testDebugUnitTest -Dshoppinglist.it.baseUrl=http://localhost:4733/
tasks.withType<Test>().configureEach {
    System.getProperty("shoppinglist.it.baseUrl")?.let { systemProperty("shoppinglist.it.baseUrl", it) }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging.interceptor)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
