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
        maven("https://repo.boox.com/repository/maven-public/") { content { includeGroup("com.onyx.android.sdk"); includeGroup("pub.devrel"); includeGroup("com.tencent"); includeGroup("com.jakewharton.hugo.fix") } }
    }
}

rootProject.name = "InkDAV"
include(":app")

include(":inkvault")
