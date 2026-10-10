plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.5.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.skriptlang.org/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    compileOnly("com.github.SkriptLang:Skript:2.16.2") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.53.2.0") {
        exclude(group = "org.slf4j")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    jar {
        archiveClassifier.set("plain")
    }
    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    test {
        useJUnitPlatform()
    }
    shadowJar {
        archiveClassifier.set("")
    }
    build {
        dependsOn(shadowJar)
    }
    runServer {
        minecraftVersion("26.2")
        javaLauncher = project.javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
        val withSkript = providers.gradleProperty("withSkript").map { it.toBoolean() }.getOrElse(false)
        runDirectory.set(layout.projectDirectory.dir(if (withSkript) "run/skript" else "run/base"))
        jvmArgs("-Xms512M", "-Xmx1G", "--enable-native-access=ALL-UNNAMED")
        if (withSkript) {
            downloadPlugins {
                url("https://github.com/SkriptLang/Skript/releases/download/2.16.2/Skript-2.16.2.jar")
            }
        }
    }
}
