plugins {
    id("paperless.android.library")
    id("paperless.hilt")
}

dependencies {
    implementation(libs.tesseract4android)
    implementation(libs.coroutines.android)
}
