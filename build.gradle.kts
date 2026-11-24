import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// 用本机已安装的 IDE 当编译/运行平台，省去下载 IDE（国内网络很实用）：
//   ./gradlew buildPlugin -PlocalIde=/Applications/GoLand.app
// 不传则使用默认的 IntelliJ IDEA，与 CI 保持一致
val localIde = providers.gradleProperty("localIde")

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        if (localIde.isPresent) {
            local(localIde.get())
        } else {
            intellijIdea("2026.3")
        }
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // 严格按 Java 21 的 API 编译，避免在本机 JBR 25 上误用高版本 API
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

// 与 Kotlin 侧的 jvmTarget 保持一致（2024.3 起 IDE 自带 JBR 21）
java {
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 目前只声明与编译平台一致的版本；放宽下限前先跑 verifyPlugin
            sinceBuild = "263"
            // 不封顶，新版本兼容性交给 verifyPlugin
            untilBuild = provider { null }
        }
    }
    // 设置页是手写的 Configurable，不需要生成可搜索选项
    buildSearchableOptions = false
}
