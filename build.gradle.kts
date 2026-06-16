import org.gradle.api.tasks.bundling.Jar

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.4.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.1.2.build.+")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.projectlombok:lombok:1.18.46")
    compileOnly("com.github.ben-manes.caffeine:caffeine:3.2.4")
    compileOnly("org.xerial:sqlite-jdbc:3.53.2.0")
    compileOnly("net.luckperms:api:5.5")
    annotationProcessor("org.projectlombok:lombok:1.18.46")
}

paperweight {
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    named<Jar>("jar") {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
    }

    runServer {
        minecraftVersion("26.1.2")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true", "-Dterminal.jline=false", "-Dterminal.ansi=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
