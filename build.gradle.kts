plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.methodflow"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 대상 IDE: IntelliJ IDEA Community 2024.2.x
        intellijIdeaCommunity("2024.2.5")
        // Kotlin / Java PSI 사용
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "243.*"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
