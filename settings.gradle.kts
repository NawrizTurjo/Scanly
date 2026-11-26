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
    ":third_party:opencv",
    ":third_party:tesseract",
)
