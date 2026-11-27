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

tasks.processResources {
    from("docs/ai-format.md") { into("ai") }
}

// 설치된 스킬은 플러그인 폴더 밖을 읽지 못하므로 명세 사본을 둔다. 명세를 고치면 이 작업을 돌리고, SkillSpecCopyTest가 어긋남을 잡는다
tasks.register("syncSkillSpec") {
    val source = layout.projectDirectory.file("docs/ai-format.md").asFile
    val target = layout.projectDirectory.file("claude-plugin/skills/jnote/ai-format.md").asFile
    inputs.file(source)
    outputs.file(target)
    doLast {
        target.writeText("<!-- Copy of docs/ai-format.md. Do not edit; change the original and run ./gradlew syncSkillSpec. -->\n" + source.readText())
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}
