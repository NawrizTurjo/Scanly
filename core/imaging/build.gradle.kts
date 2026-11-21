plugins { id("paperless.android.library") }

android {
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    sourceSets["androidTest"].assets.srcDir(rootProject.file("test-data/imaging-goldens"))
}

dependencies {
    api(libs.opencv)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.espresso.core)
}
