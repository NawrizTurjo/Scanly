plugins { id("paperless.android.feature") }

android {
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:pdf"))
    implementation(project(":core:imaging"))
    implementation(libs.activity.compose)
    implementation(libs.work.runtime.ktx)
    androidTestImplementation(libs.test.junit)
    androidTestRuntimeOnly(libs.espresso.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
