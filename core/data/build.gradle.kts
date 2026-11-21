plugins {
    id("paperless.android.library")
    id("paperless.hilt")
}

android {
    // Room exports schemas here; migration tests read them as test assets.
    sourceSets["test"].assets.srcDir(file("schemas"))
    testOptions.unitTests.isIncludeAndroidResources = true
}

ksp {
    arg("room.schemaLocation", file("schemas").path)
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    api(project(":core:model"))
    implementation(project(":core:pdf"))
    implementation(project(":core:imaging"))
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
