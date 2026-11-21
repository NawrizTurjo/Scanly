plugins {
    id("paperless.android.library")
    id("paperless.hilt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
