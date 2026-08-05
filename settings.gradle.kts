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
        maven {
            url = uri("app/libs/ffmpeg-kit-next-maven")
            content {
                includeModule("com.arthenica", "ffmpeg-kit-next")
            }
        }
        maven {
            url = uri("app/libs/mpv-runtime-maven")
            content {
                includeModule("com.subtitleedit.native", "mpv-android-runtime")
            }
        }
        maven("https://maven.aliyun.com/repository/public") {
            content {
                includeGroup("com.arthenica")
            }
        }
    }
}

rootProject.name = "SubtitleEditforAndroid"
include(":app")
