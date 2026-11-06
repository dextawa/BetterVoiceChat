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

// Libraries copied into the Forge / NeoForge mod jar (resolved with their dependencies).
val bundled = configurations.create("bundled") { isTransitive = true }

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

    // Voice engine, protocol and Opus codec.
    "implementation"("top.dext.bvc:bvc-client-core:${prop("mod.version")}")
    if (loader == "fabric") {
        // Fabric loads nested jars reliably, in dev and in production.
        "include"("top.dext.bvc:bvc-client-core:${prop("mod.version")}")
        "include"("top.dext.bvc:protocol:${prop("mod.version")}")
        "include"("io.github.jaredmdobson:concentus:1.0.2")
    } else {
        // Forge 1.16.5 has no jar-in-jar, and FML's dev runs do not put nested jars on the mod
        // classpath: copy the classes into the mod jar instead (see the jar task), and give dev
        // runs the libraries directly.
        "bundled"("top.dext.bvc:bvc-client-core:${prop("mod.version")}")
        "forgeRuntimeLibrary"("top.dext.bvc:bvc-client-core:${prop("mod.version")}")
    }
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
    findProperty("bvc.quickplay")?.toString()?.let {
        if (stonecutter.eval(minecraftVersion, ">=1.20")) {
            programArgs("--quickPlayMultiplayer", it)
        } else {
            // Before 1.20 the client only knows --server / --port.
            programArgs("--server", it.substringBeforeLast(':'), "--port", it.substringAfterLast(':'))
            // 1.16.5 loses the --server connect screen behind the loading overlay (see devJoin).
            if (stonecutter.eval(minecraftVersion, "<1.17")) vmArgs("-Dbvc.devJoin=$it")
        }
    }
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
        // Maven ranges reject identical boundaries: a single version is written "[x]".
        // Fabric API's mod id was "fabric" before 1.17.
        "fabricApiId" to if (stonecutter.eval(minecraftVersion, "<1.17")) "fabric" else "fabric-api",
        "forgeRange" to if (low == high) "[$low]" else "[$low,$high]",
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "bvc-mod.properties")) { expand(props) }
    if (loader != "fabric") exclude("fabric.mod.json")
    if (loader == "fabric") exclude("META-INF/neoforge.mods.toml", "pack.mcmeta")
    if (legacyModsToml) rename("neoforge\\.mods\\.toml", "mods.toml")
    // Forge only loads a mod's assets (translations) when the jar has a pack.mcmeta.
    if (loader == "forge") {
        val packFormat = when {
            stonecutter.eval(minecraftVersion, "<1.17") -> 6
            stonecutter.eval(minecraftVersion, "<1.19") -> 8
            stonecutter.eval(minecraftVersion, "<1.19.3") -> 9
            stonecutter.eval(minecraftVersion, "<1.20") -> 13
            else -> 15
        }
        inputs.property("packFormat", packFormat)
        doLast {
            destinationDir.resolve("pack.mcmeta").writeText(
                """{"pack":{"description":"BetterVoiceChat","pack_format":$packFormat}}""")
        }
    }
}

tasks.named<Jar>("jar") {
    from(rootProject.file("../../LICENSE")) { rename { "LICENSE_BetterVoiceChat" } }
    // Concentus is BSD-licensed; its classes keep their own package, so no relocation is needed.
    from(provider { bundled.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
