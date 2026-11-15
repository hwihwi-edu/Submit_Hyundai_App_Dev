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
        // Pleos Playground SDK 저장소. ai.pleos.playground 그룹(하위 그룹 포함)만 여기서 받는다.
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            content { includeGroupByRegex("ai\\.pleos\\.playground(\\..+)?") }
        }
    }
}

rootProject.name = "travel-map-app"
include(":app")
