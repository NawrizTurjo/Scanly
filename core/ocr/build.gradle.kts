plugins {
    id("paperless.android.library")
    id("paperless.hilt")
}

android {
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    sourceSets["androidTest"].assets.srcDir(rootProject.file("test-data/scanner/synthetic/clean"))
}

dependencies {
    implementation(project(":third_party:tesseract"))
    implementation(libs.coroutines.android)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.espresso.core)
    // The standalone probe APK needs the shared C++ runtime packaged by OpenCV.
    androidTestRuntimeOnly(project(":third_party:opencv"))
}
