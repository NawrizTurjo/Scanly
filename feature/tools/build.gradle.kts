plugins { id("paperless.android.feature") }

android { testOptions.unitTests.isIncludeAndroidResources = true }

dependencies {
    implementation(project(":core:pdf"))
    implementation(libs.activity.compose)
    implementation(libs.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
