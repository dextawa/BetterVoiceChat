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

        mc("1.20.1", "fabric", "forge")
        mc("1.20.4", "fabric", "neoforge")
        mc("1.20.6", "fabric", "neoforge")
        mc("26.3", "fabric", "neoforge")

        vcsVersion = "26.3-fabric"
    }
}

rootProject.name = "bettervoicechat-modern"

// The Minecraft-independent client core comes straight from the main build.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("top.dext.bvc:bvc-client-core")).using(project(":client:core"))
        substitute(module("top.dext.bvc:protocol")).using(project(":protocol"))
    }
}
