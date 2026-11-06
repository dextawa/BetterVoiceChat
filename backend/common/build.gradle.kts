plugins {
    id("bvc.java8")
}

description = "Platform-independent sub-server core, shared by the Bukkit plugin and the server mods"

dependencies {
    api(project(":protocol"))
    api(project(":api"))
}
