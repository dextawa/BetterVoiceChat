rootProject.name = "BetterVoiceChat"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            content {
                includeGroupAndSubgroups("com.velocitypowered")
                includeGroupAndSubgroups("io.papermc")
                // paper-api's old bungeecord-chat build lives here
                includeGroup("net.md-5")
            }
        }
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            content { includeGroup("org.spigotmc"); includeGroup("org.bukkit") }
        }
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            content { includeGroup("net.md-5") }
            mavenContent { snapshotsOnly() }
        }
        // BungeeCord's protocol module depends on Mojang's brigadier.
        maven("https://libraries.minecraft.net/") {
            content { includeGroup("com.mojang") }
        }
    }
}

include(
    "protocol",
    "api",
    "proxy:common",
    "proxy:velocity",
    "proxy:bungee",
    "backend:common",
    "backend:bukkit",
    "client:core",
)
