pluginManagement {
    repositories {
        // Зеркала Google Maven / Maven Central: в этой сети TLS к dl.google.com
        // обрывается (DPI), а зеркала отдают те же артефакты.
        maven {
            url = uri("https://repo.huaweicloud.com/repository/maven/")
            content { excludeGroupByRegex("com\\.github\\..*") }
        }
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            content { excludeGroupByRegex("com\\.github\\..*") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://repo.huaweicloud.com/repository/maven/")
            content { excludeGroupByRegex("com\\.github\\..*") }
        }
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            content { excludeGroupByRegex("com\\.github\\..*") }
        }
        google()
        mavenCentral()
        // JitPack отдаёт NewPipeExtractor и его форк nanojson.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}
rootProject.name = "Volna"
include(":app")
