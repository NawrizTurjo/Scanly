plugins { id("paperless.android.feature") }

dependencies {
    implementation(project(":feature:reader"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:pdf"))
    implementation(libs.reorderable)
    implementation(libs.coil.compose)
    implementation(libs.hilt.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
