import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "anytab"

// pluginManagement 块先于脚本其余部分求值，读不到脚本级变量，所以直接读系统属性/环境变量。
// 国内网络下载 Gradle 插件很慢，开启后优先走阿里云镜像，任选一种方式：
//   ./gradlew buildPlugin -DcnMirror=true
//   ~/.gradle/gradle.properties 里加 systemProp.cnMirror=true
//   环境变量 CN_MIRROR=true
pluginManagement {
    repositories {
        if (System.getProperty("cnMirror")?.toBoolean() == true || System.getenv("CN_MIRROR")?.toBoolean() == true) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.21"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        if (System.getProperty("cnMirror")?.toBoolean() == true || System.getenv("CN_MIRROR")?.toBoolean() == true) {
            maven("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
