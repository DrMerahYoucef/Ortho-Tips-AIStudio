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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // TDLib Android bindings are only published on JitPack. The content filter
    // keeps every other dependency resolving from google()/mavenCentral() exactly
    // as before, so nothing already working can start resolving from here.
    maven("https://jitpack.io") {
      content { includeGroup("com.github.tdlibx") }
    }
  }
}

rootProject.name = "Ortho Tips"

include(":app")
