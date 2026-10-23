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
    }
}
rootProject.name = "tandem"
include(":core", ":server")
// The server Docker image builds without an Android SDK, so it leaves the app out.
if (System.getenv("TANDEM_SERVER_ONLY") == null) include(":app")
