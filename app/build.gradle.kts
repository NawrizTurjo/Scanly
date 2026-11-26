plugins {
    id("paperless.android.application")
    id("paperless.hilt")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    buildFeatures.compose = true
    // AndroidX ships translations for ~89 languages; Scanly is English (Bengali kept for OCR users).
    androidResources.localeFilters += listOf("en", "bn")
    packaging {
        resources {
            // BouncyCastle (via PdfBox) ships ~4 MB of post-quantum key tables. R8 strips every
            // org.bouncycastle.pqc class, so these data files are never read.
            excludes += "org/bouncycastle/pqc/**"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:tools"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:camera"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:export"))
    implementation(project(":core:imaging"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.espresso.core)
}
