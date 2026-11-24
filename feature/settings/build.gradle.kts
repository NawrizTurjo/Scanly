plugins { id("paperless.android.feature") }

android {
    defaultConfig.testInstrumentationRunner =
        "dev.turjo.paperless.feature.settings.UpdateReleaseFixtureInstrumentation"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.hilt.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
