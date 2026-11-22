plugins { id("paperless.android.library.compose") }

dependencies {
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
