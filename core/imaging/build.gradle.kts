plugins { id("paperless.android.library") }

android {
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    sourceSets["androidTest"].assets.srcDirs(
        rootProject.file("test-data/imaging-goldens"),
        rootProject.file("test-data/special"),
    )
}

dependencies {
    api(project(":third_party:opencv"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.espresso.core)
}
