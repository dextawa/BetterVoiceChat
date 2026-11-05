// Shared Java setup for every BVC module. Pick the bytecode level with
// bvc.java8 (runs on 1.8 servers/clients) or bvc.java17 (proxy side).
plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // --release 8 is deprecated on modern JDKs but still supported; silence the noise.
    options.compilerArgs.addAll(listOf("-Xlint:-options", "-parameters"))
}

// JUnit 6 needs Java 17, so tests always compile at 17 even when main targets 8.
tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE_BetterVoiceChat" }
    }
}
