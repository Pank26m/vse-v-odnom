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
        // локальная копия больших jar, которые прокси Maven Central отдаёт с ошибкой 429
        mavenLocal { content { includeGroup("org.robolectric") } }
        google()
        mavenCentral()
    }
}
rootProject.name = "vse-v-odnom"
include(":app")
