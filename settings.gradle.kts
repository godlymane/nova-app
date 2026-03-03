pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Nova"
include(":app")

// MLC-LLM: include mlc4j as local subproject when built
// Run `mlc_llm package` in the mlc-llm repo, then copy dist/lib/mlc4j here
if (file("mlc4j").exists()) {
    include(":mlc4j")
}
