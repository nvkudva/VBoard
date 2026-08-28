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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // sherpa-onnx ships its Android AAR through JitPack (see jitpack.yml upstream).
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa") }
        }
    }
}

rootProject.name = "VBoard"

include(":core")

// The :app module needs the Android SDK (and Google Maven access) to build.
// Set vboard.skipAndroid=true in local environments that only run the pure-JVM
// :core module and its tests (e.g. sandboxes without dl.google.com access).
val skipAndroid = providers.gradleProperty("vboard.skipAndroid").orNull == "true"
if (!skipAndroid) {
    include(":app")
}
