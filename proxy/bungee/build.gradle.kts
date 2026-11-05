plugins {
    id("bvc.java17")
    alias(libs.plugins.shadow)
}

description = "BVC proxy plugin for BungeeCord"

dependencies {
    implementation(project(":proxy:common"))
    compileOnly(libs.bungeecord.api)
    compileOnly(libs.netty.handler)
    compileOnly(libs.netty.codec)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("bungee.yml") { expand(props) }
}

tasks.shadowJar {
    archiveBaseName.set("BetterVoiceChat-Bungee")
    archiveClassifier.set("")
    relocate("org.yaml.snakeyaml", "top.dext.bvc.lib.snakeyaml")
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
