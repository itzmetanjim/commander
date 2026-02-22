plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
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
        register("colonel") {
            id = "org.tanjim.colonel"
            implementationClass = "org.tanjim.colonel.ColonelParser"
        }
    }
}
