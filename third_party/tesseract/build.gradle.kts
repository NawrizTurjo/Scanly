plugins { id("paperless.android.library") }

android {
    lint.abortOnError = false
}

dependencies {
    compileOnly(libs.core.ktx)
}
