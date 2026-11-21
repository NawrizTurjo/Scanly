plugins { id("paperless.android.library") }

android {
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    testOptions.unitTests.isIncludeAndroidResources = true
}

tasks.withType<Test>().configureEach {
    val testData = rootProject.layout.projectDirectory.dir("test-data")
    inputs.dir(testData.dir("imaging-goldens/warped"))
    systemProperty("paperless.testData", testData.asFile.absolutePath)
    systemProperty("maven.repo.local", layout.buildDirectory.dir("robolectric-maven").get().asFile.absolutePath)
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.pdfbox.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
}
