plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    signing
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "org.tanjim"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.palantir.javapoet:javapoet:0.6.0")
    implementation("com.github.h0tk3y.betterParse:better-parse:0.4.4")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

extensions.configure<GradlePluginDevelopmentExtension>("gradlePlugin") {
    plugins {
        register("commander") {
            id = "org.tanjim.commander"
            implementationClass = "org.tanjim.commander.CommanderParser"
            displayName = "Commander"
            description = "A better alternative to Brigadier for FabricMC mods"
        }
    }
}
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(groupId = "org.tanjim", artifactId = "commander", version = "1.0.0")
    pom {
        name.set("Commander")
        description.set("A better alternative to Brigadier for FabricMC mods")
        url.set("https://github.com/itzmetanjim/commander")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit/")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("itzmetanjim")
                name.set("Tanjim Kamal")
                email.set("tanjimkamal1@gmail.com")
            }
        }
        scm{
            connection.set("scm:git:github.com/itzmetanjim/commander.git")
            developerConnection.set("scm:git:ssh://github.com/itzmetanjim/commander.git")
            url.set("https://github.com/itzmetanjim/commander")
        }
    }
}
