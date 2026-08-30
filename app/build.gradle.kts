plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.projectnuke.keplernightlab"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    signingConfigs {
        // Project-pinned DEBUG identity. A local override lets the user's
        // existing machine-local debug identity be preserved without mutating
        // the checked-in fallback key. Never reuse either key for release.
        getByName("debug") {
            val localDebugKeystore = rootProject.file("app/local-debug.jks")
            val pinnedDebugKeystore = rootProject.file("app/kepler-debug.jks")
            storeFile = when {
                localDebugKeystore.isFile -> localDebugKeystore
                pinnedDebugKeystore.isFile -> pinnedDebugKeystore
                else -> error("Debug signing requires app/local-debug.jks or app/kepler-debug.jks")
            }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.projectnuke.keplernightlab"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

tasks.register("verifyDebugSigningSafety") {
    group = "verification"
    description = "Checks that debug signing uses only the intended project keystores."
    doLast {
        fun gitContainsTracked(path: String): Boolean {
            val process = ProcessBuilder("git", "ls-files", "--error-unmatch", path)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            return process.waitFor() == 0
        }

        val localPath = rootProject.file("app/local-debug.jks")
        val pinnedPath = rootProject.file("app/kepler-debug.jks")
        check(!gitContainsTracked("app/local-debug.jks")) {
            "Developer-local app/local-debug.jks must never be tracked"
        }
        check(gitContainsTracked("app/kepler-debug.jks")) {
            "The project-pinned fallback app/kepler-debug.jks must be tracked"
        }
        check(pinnedPath.isFile) { "Missing project-pinned fallback debug keystore" }
        check(localPath.isFile || pinnedPath.isFile) {
            "Debug signing has no usable project keystore"
        }

        val source = project.file("build.gradle.kts").readText()
        check("app/local-debug.jks" in source && "app/kepler-debug.jks" in source) {
            "Debug signing must reference only the intended project keystore paths"
        }
        val signingSource = source.substringBefore("defaultConfig")
        check("debug.keystore" !in signingSource) {
            "Debug signing must not fall back to a machine-default debug keystore"
        }
        val releaseStart = source.indexOf("release {")
        check(releaseStart >= 0) { "Release build type is missing" }
        val releaseEnd = source.indexOf("compileOptions", releaseStart)
        check(releaseEnd > releaseStart) { "Release build type boundary is missing" }
        val releaseBlock = source.substring(releaseStart, releaseEnd)
        check("signingConfig" !in releaseBlock) {
            "Release signing must remain independent from the debug signing config"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
