plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.driflux.jnote"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

// AI 형식 명세는 docs/ai-format.md 하나뿐이고, "AI용 형식 안내 복사"가 빌드 때 넣은 이 사본을 읽는다
tasks.processResources {
    from("docs/ai-format.md") { into("ai") }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}
