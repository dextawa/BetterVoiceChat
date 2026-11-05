plugins {
    id("bvc.java17")
    alias(libs.plugins.shadow)
}

description = "BVC proxy plugin for Velocity"

dependencies {
    implementation(project(":proxy:common"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    compileOnly(libs.netty.handler)
    compileOnly(libs.netty.codec)
}

// @Plugin(version = ...) needs a compile-time constant.
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildinfo")
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("top/dext/bvc/proxy/velocity/BuildInfo.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package top.dext.bvc.proxy.velocity;

            final class BuildInfo {
                static final String VERSION = "$pluginVersion";

                private BuildInfo() {
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.main {
    java.srcDir(generateBuildInfo)
}

tasks.shadowJar {
    archiveBaseName.set("BetterVoiceChat-Velocity")
    archiveClassifier.set("")
    relocate("org.yaml.snakeyaml", "top.dext.bvc.lib.snakeyaml")
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
