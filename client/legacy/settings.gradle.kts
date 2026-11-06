pluginManagement {
    repositories {
        maven("https://repo.essential.gg/repository/maven-public")
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Minecraft 1.8.9 / 1.12.2 run on Java 8: let Gradle download it if it is missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "bettervoicechat-legacy"

// One project per Minecraft version. The APIs differ too much (MCP names, text components,
// rendering) for comment-based preprocessing to pay off; shared, Minecraft-free code lives in
// common/.
include("1.8.9", "1.9.4", "1.10.2", "1.11.2", "1.12.2")

// The Minecraft-independent client core comes straight from the main build.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("top.dext.bvc:bvc-client-core")).using(project(":client:core"))
        substitute(module("top.dext.bvc:protocol")).using(project(":protocol"))
    }
}
