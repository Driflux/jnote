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
        pluginVerifier()
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

tasks.test {
    // 테스트가 -PpluginVersion 같은 덮어쓰기까지 반영한 실제 버전을 보게 한다
    systemProperty("jnote.pluginVersion", version.toString())
    inputs.file("docs/ai-format.md")
    inputs.dir("claude-plugin")
    inputs.dir(".claude-plugin")
    inputs.file("src/main/resources/META-INF/plugin.xml")
    inputs.file("LICENSE")
    inputs.file("gradle.properties")
}

// 개발용 버전(-SNAPSHOT)이 Marketplace에 올라가는 일을 막는다
tasks.named("publishPlugin") {
    val releaseVersion = version.toString()
    doFirst {
        check(!releaseVersion.endsWith("-SNAPSHOT")) { "pluginVersion is $releaseVersion; set the release version first (docs/release.md)" }
    }
}

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
    // 토큰은 저장소에 두지 않고 환경 변수로만 받는다
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.1")
        }
    }
}
