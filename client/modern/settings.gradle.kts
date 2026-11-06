pluginManagement {
    repositories {
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.kikugie.dev/releases")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

// One node per Minecraft version and loader; all nodes share src/. Loader-specific code is
// switched with Stonecutter constants (//? if fabric { ... }).
stonecutter {
    create(rootProject) {
        fun mc(version: String, vararg loaders: String) = loaders.forEach { version("$version-$it", version) }

        mc("1.16.5", "fabric", "forge")
        mc("1.18.2", "fabric", "forge")
        mc("1.19.2", "fabric", "forge")
        mc("1.19.4", "fabric", "forge")
        mc("1.20.1", "fabric", "forge")
        mc("1.20.4", "fabric", "neoforge")
        mc("1.20.6", "fabric", "neoforge")
        mc("1.21.1", "fabric", "neoforge")
        mc("1.21.4", "fabric", "neoforge")
        mc("1.21.5", "fabric", "neoforge")
        mc("1.21.8", "fabric", "neoforge")
        mc("1.21.10", "fabric", "neoforge")
        mc("1.21.11", "fabric", "neoforge")
        mc("26.1.2", "fabric", "neoforge")
        mc("26.2", "fabric", "neoforge")
        mc("26.3", "fabric", "neoforge")

        vcsVersion = "26.3-fabric"
    }
}

rootProject.name = "bettervoicechat-modern"

// The Minecraft-independent client and sub-server cores come straight from the main build.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("top.dext.bvc:bvc-client-core")).using(project(":client:core"))
        substitute(module("top.dext.bvc:protocol")).using(project(":protocol"))
        substitute(module("top.dext.bvc:bvc-backend-common")).using(project(":backend:common"))
        substitute(module("top.dext.bvc:api")).using(project(":api"))
    }
}
