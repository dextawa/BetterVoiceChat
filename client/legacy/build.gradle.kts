plugins {
    id("gg.essential.loom") version "1.15.52" apply false
    id("dev.architectury.architectury-pack200") version "0.1.3" apply false
}

subprojects {
    repositories {
        maven("https://maven.minecraftforge.net/") {
            // Old Forge-era libraries (scala, etc.) are published as bare jars without a POM.
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        // Intermediary mappings for pre-1.14 Minecraft (Loom needs them even for Forge).
        maven("https://repo.legacyfabric.net/legacyfabric/") {
            content { includeModule("net.fabricmc", "intermediary") }
        }
        mavenCentral()
    }

    apply(plugin = "gg.essential.loom")
    apply(plugin = "dev.architectury.architectury-pack200")

    fun prop(name: String) = project.property(name) as String
    val minecraftVersion = project.name
    val loom = extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>()

    group = prop("mod.group")
    version = "${prop("mod.version")}+$minecraftVersion-forge"
    extensions.getByType<BasePluginExtension>().archivesName.set("BetterVoiceChat")


    // Libraries copied into the mod jar: legacy Forge has no jar-in-jar.
    val bundled = configurations.create("bundled") { isTransitive = true }

    dependencies {
        "minecraft"("com.mojang:minecraft:$minecraftVersion")
        "mappings"(prop("deps.mappings"))
        "forge"(prop("deps.forge"))
        "implementation"("top.dext.bvc:bvc-client-core:${prop("mod.version")}")
        "bundled"("top.dext.bvc:bvc-client-core:${prop("mod.version")}")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(8))
    }
    extensions.configure<SourceSetContainer> {
        named("main") {
            java.srcDir(rootProject.file("common/java"))
            resources.srcDir(rootProject.file("common/resources"))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    loom.forge.pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())

    // Dev client: `-Pbvc.quickplay=host:port` joins that server on start; `-Pbvc.user=Name` sets the offline name.
    loom.runs.named("client") {
        findProperty("bvc.quickplay")?.toString()?.let {
            programArgs("--server", it.substringBeforeLast(':'), "--port", it.substringAfterLast(':'))
        }
        findProperty("bvc.user")?.let { programArgs("--username", it.toString()) }
    }

    tasks.named<ProcessResources>("processResources") {
        val props = mapOf("version" to project.version.toString(), "minecraft" to minecraftVersion)
        inputs.properties(props)
        filesMatching("mcmod.info") { expand(props) }
        filesMatching("bvc-mod.properties") { expand(props) }
        // Translations: 1.8.9 wants en_US.lang, 1.12.2 en_us.lang (same key=value format).
        if (minecraftVersion == "1.8.9") {
            rename("en_us[.]lang", "en_US.lang")
            rename("zh_cn[.]lang", "zh_CN.lang")
        }
    }

    tasks.named<Jar>("jar") {
        from(rootProject.file("../../LICENSE")) { rename { "LICENSE_BetterVoiceChat" } }
        // Client core, protocol and Concentus (BSD); none of them reference Minecraft.
        from(provider { bundled.map { zipTree(it) } }) {
            exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

