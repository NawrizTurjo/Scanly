pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "paperless"
include(
    ":app",
    ":core:designsystem",
    ":core:model",
    ":core:data",
    ":core:imaging",
    ":core:pdf",
    ":core:ocr",
    ":feature:library",
    ":feature:camera",
    ":feature:editor",
    ":feature:export",
    ":feature:reader",
    ":feature:tools",
    ":feature:settings",
)
