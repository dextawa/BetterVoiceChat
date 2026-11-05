import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("dev.architectury.loom") apply false
    id("dev.architectury.loom-no-remap") apply false
}

fun prop(name: String) = project.property(name) as String

val minecraftVersion = stonecutter.current.version
val loader = prop("loom.platform")
val javaVersion = prop("java.version").toInt()
// 26.x ships unobfuscated; older versions are remapped with Mojang's official mappings.
val obfuscated = stonecutter.eval(minecraftVersion, "<26")

apply(plugin = if (obfuscated) "dev.architectury.loom" else "dev.architectury.loom-no-remap")
val loom = extensions.getByType<LoomGradleExtensionAPI>()

group = prop("mod.group")
version = "${prop("mod.version")}+$minecraftVersion-$loader"
base.archivesName.set("BetterVoiceChat")

repositories {
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.minecraftforge.net/")
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    // Remapped builds must pull loader artifacts through the mod* configurations.
    val modImpl = if (obfuscated) "modImplementation" else "implementation"
    if (obfuscated) {
        "mappings"(loom.officialMojangMappings())
    }
    when (loader) {
        "fabric" -> {
            modImpl("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
            modImpl("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")
        }
        "neoforge" -> "neoForge"("net.neoforged:neoforge:${prop("deps.neoforge")}")
        "forge" -> "forge"("net.minecraftforge:forge:${prop("deps.forge")}")
    }

    // Voice engine, protocol and Opus codec, nested into the mod jar.
    "implementation"("top.dext.bvc:bvc-client-core:${prop("mod.version")}")
    "include"("top.dext.bvc:bvc-client-core:${prop("mod.version")}")
    "include"("top.dext.bvc:protocol:${prop("mod.version")}")
    "include"("io.github.jaredmdobson:concentus:1.0.2")
}

// Only JDK 21 and 25 are needed on the build machine: Java 17 targets compile with --release 17.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(maxOf(javaVersion, 21)))
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
}

// Dev client: `-Pbvc.quickplay=host:port` joins that server on start; `-Pbvc.user=Name` sets the offline name.
loom.runs.named("client") {
    findProperty("bvc.quickplay")?.let { programArgs("--quickPlayMultiplayer", it.toString()) }
    findProperty("bvc.user")?.let { programArgs("--username", it.toString()) }
}

// Each node only has its own loader on the classpath: compile only that loader's entry point.
val otherLoaders = listOf("fabric", "neoforge", "forge") - loader
tasks.named<JavaCompile>("compileJava") {
    otherLoaders.forEach { exclude("top/dext/bvc/mod/$it/**") }
}

// Forge, and NeoForge before 1.20.5, read META-INF/mods.toml instead of neoforge.mods.toml.
val legacyModsToml = loader == "forge" || (loader == "neoforge" && stonecutter.eval(minecraftVersion, "<1.20.5"))

tasks.named<ProcessResources>("processResources") {
    // mc.range = "<lowest> <highest>" Minecraft versions this node's jar is declared to run on.
    val range = (findProperty("mc.range") as String? ?: minecraftVersion).split(" ")
    val low = range.first()
    val high = range.last()
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraft" to minecraftVersion,
        "fabricRange" to ">=$low <=$high",
        "forgeRange" to "[$low,$high]",
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "bvc-mod.properties")) { expand(props) }
    if (loader != "fabric") exclude("fabric.mod.json")
    if (loader == "fabric") exclude("META-INF/neoforge.mods.toml", "pack.mcmeta")
    if (legacyModsToml) rename("neoforge\\.mods\\.toml", "mods.toml")
}

tasks.named<Jar>("jar") {
    from(rootProject.file("../../LICENSE")) { rename { "LICENSE_BetterVoiceChat" } }
}
