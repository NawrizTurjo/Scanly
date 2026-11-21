plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            this.id = "paperless.android.application"
            implementationClass = "dev.turjo.paperless.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            this.id = "paperless.android.library"
            implementationClass = "dev.turjo.paperless.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            this.id = "paperless.android.library.compose"
            implementationClass = "dev.turjo.paperless.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            this.id = "paperless.android.feature"
            implementationClass = "dev.turjo.paperless.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("hilt") {
            this.id = "paperless.hilt"
            implementationClass = "dev.turjo.paperless.buildlogic.HiltConventionPlugin"
        }
    }
}
