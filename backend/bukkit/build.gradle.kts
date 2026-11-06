plugins {
    id("bvc.java8")
    alias(libs.plugins.shadow)
}

description = "BVC sub-server plugin for Spigot / Paper / Folia (1.8+)"

// Folia-only code: compiled against the Paper API at Java 17 and loaded by reflection only when
// Folia is detected, so the rest of the jar still loads on 1.8 / Java 8 servers.
val folia: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
}

dependencies {
    implementation(project(":backend:common"))
    // Compile against the oldest API so the jar loads on every version. Its bungeecord-chat
    // snapshot is no longer hosted anywhere, and BVC does not need it.
    compileOnly(libs.spigot.api.legacy) {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }
    "foliaCompileOnly"(libs.paper.api.folia)
}

tasks.named<JavaCompile>("compileFoliaJava") {
    options.release.set(17)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveBaseName.set("BetterVoiceChat-Bukkit")
    archiveClassifier.set("")
    from(folia.output)
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
