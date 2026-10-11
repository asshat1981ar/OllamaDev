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

// NOTE (2026-07-29, env workaround — revert when network/tooling is healthy):
// foojay toolchain resolver disabled. No toolchain spec exists in this build
// (compileOptions target JavaVersion.VERSION_11 only), and in the current
// sandbox (broken IPv4 loopback + pruned Gradle cache) resolving this settings
// plugin from plugins.gradle.org fails the whole configuration phase.
// plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "Ollama Swarm"

include(":app")
