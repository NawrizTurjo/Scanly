package dev.turjo.paperless.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureAndroid(this@with)
            namespace = "dev.turjo.paperless"
            defaultConfig {
                applicationId = "dev.turjo.paperless"
                targetSdk = 37
                versionCode = 6
                versionName = "0.4.1"
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            // The emulator needs x86_64; the Pixel only arm64 (keeps the release APK small: OpenCV is ~33 MB per ABI).
            buildTypes.named("debug") { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
            buildTypes.named("release") {
                ndk { abiFilters += listOf("arm64-v8a") }
                signingConfig = releaseSigning(this@with) ?: signingConfigs.getByName("debug")
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
            lint.checkDependencies = true
        }
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroid(this@with)
            namespace = "dev.turjo.paperless." + path.removePrefix(":").replace(':', '.')
        }
    }
}

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("paperless.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        dependencies.add("implementation", dependencies.platform(library("compose-bom")))
        dependencies.add("implementation", library("compose-material3"))
        Unit
    }
}

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("paperless.android.library.compose")
        pluginManager.apply("paperless.hilt")
        dependencies.add("implementation", project(":core:designsystem"))
        dependencies.add("implementation", library("lifecycle-runtime-compose"))
        dependencies.add("implementation", library("lifecycle-viewmodel-compose"))
        Unit
    }
}

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        dependencies.add("implementation", library("hilt-android"))
        dependencies.add("ksp", library("hilt-compiler"))
        Unit
    }
}

/**
 * Release signing from the gitignored keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
 * Without it (fresh checkout) release builds fall back to the debug key so they still build.
 */
private fun ApplicationExtension.releaseSigning(project: Project): com.android.build.api.dsl.ApkSigningConfig? {
    val file = project.rootProject.file("keystore.properties")
    if (!file.isFile) return null
    val props = java.util.Properties().apply { file.inputStream().use(::load) }
    return signingConfigs.maybeCreate("release").apply {
        storeFile = project.rootProject.file(props.getProperty("storeFile"))
        storePassword = props.getProperty("storePassword")
        keyAlias = props.getProperty("keyAlias")
        keyPassword = props.getProperty("keyPassword")
    }
}

private fun CommonExtension.configureAndroid(project: Project) {
    // Compose 1.13 (BOM 2026.11.01) AARs require compile SDK 37.1.
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370" // lets AGP strip native libs (OpenCV ships unstripped)
    defaultConfig.minSdk = 31
    compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    project.tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    lint.apply {
        abortOnError = true
        warningsAsErrors = false
    }
}

private fun Project.library(alias: String) =
    extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary(alias).get()
