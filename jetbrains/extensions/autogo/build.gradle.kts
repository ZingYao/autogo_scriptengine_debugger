plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

tasks {
    val buildBundledGluaTools by registering(Exec::class) {
        // IDEA 独立构建时同步生成 Windows/macOS 双架构的 gluals 与 gluac 资源。
        workingDir(rootProject.projectDir.resolve("../../.."))
        commandLine("bash", "scripts/build-glua-tools-platforms.sh")
    }
    processResources {
        dependsOn(buildBundledGluaTools)
    }
    named<JavaExec>("runIde") {
        // 为 Gradle 沙盒设置独立 Dock 名称，便于自动化验收精准定位而不触碰正式 IDEA。
        jvmArgs("-Xdock:name=AutoGo IDEA Sandbox", "-Dapple.awt.application.name=AutoGo IDEA Sandbox")
    }
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
    }
    buildSearchableOptions { enabled = false }
    prepareJarSearchableOptions { enabled = false }
    test { useJUnitPlatform() }
}
