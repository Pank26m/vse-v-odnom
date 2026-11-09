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
    }
}
rootProject.name = "vse-v-odnom"
include(":app")
