plugins {
    id("paperless.android.library")
    id("paperless.hilt")
}

android {
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    sourceSets["androidTest"].assets.srcDir(rootProject.file("test-data/imaging-goldens/inputs"))
    // Room exports schemas here; migration tests read them as test assets.
    sourceSets["test"].assets.srcDir(file("schemas"))
    testOptions.unitTests.isIncludeAndroidResources = true
}

ksp {
    arg("room.schemaLocation", file("schemas").path)
}

tasks.withType<Test>().configureEach {
    val fixtures = rootProject.layout.projectDirectory.dir("test-data/pdf")
    inputs.dir(fixtures)
    systemProperty("paperless.pdfFixtures", fixtures.asFile.absolutePath)
}

dependencies {
    api(project(":core:ocr"))
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.room.runtime)
    implementation(libs.work.runtime.ktx)
    implementation(libs.pdfbox.android)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    api(project(":core:model"))
    implementation(project(":core:pdf"))
    api(project(":core:imaging"))
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
