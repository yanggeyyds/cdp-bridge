// 顶层 settings：声明插件版本仓库与模块
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
        // Shizuku 早期版本发布在 mavenCentral，新版同样在 mavenCentral
        // 来源: https://central.sonatype.com/artifact/dev.rikka.shizuku/api/versions
        // libsu (RootService) 发布在 jitpack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "CdpBridge"
include(":app")
