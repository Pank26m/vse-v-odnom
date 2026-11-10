pluginManagement {
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
        // GLMap (движок карт Guru Maps): glmap, glroute, glsearch
        maven { url = uri("https://maven.globus.software/artifactory/libs") }
        // TDLib (официальная библиотека Telegram, сборка tdlibx) — только она
        maven { url = uri("https://jitpack.io"); content { includeGroup("com.github.tdlibx") } }
    }
}
rootProject.name = "vse-v-odnom"
include(":app")
