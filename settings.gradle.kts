// Android uygulaması yalnızca `gradle -Pandroid=true` ile dahil edilir: Google depoları
// (dl.google.com) bazı ortamlarda erişilemez; varsayılan derleme bunlara bağlı değildir.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        if (providers.gradleProperty("android").isPresent) google()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (providers.gradleProperty("android").isPresent) google()
    }
}

rootProject.name = "Hypogeum"

include(":domain", ":data", ":cli")
if (providers.gradleProperty("android").isPresent) include(":app")
