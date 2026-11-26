plugins { id("paperless.android.library.compose") }

dependencies {
    implementation(project(":core:model"))
    implementation(libs.haze)
    implementation(libs.haze.glass)
    testImplementation(libs.junit)
}
