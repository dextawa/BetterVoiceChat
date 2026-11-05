plugins {
    id("bvc.java17")
}

description = "Platform-independent proxy core"

dependencies {
    api(project(":protocol"))
    api(project(":api"))
    // Provided at runtime by Velocity / BungeeCord.
    compileOnly(libs.netty.handler)
    compileOnly(libs.netty.codec)
    compileOnly(libs.gson)
    implementation(libs.snakeyaml)
    implementation(libs.h2)

    testImplementation(libs.netty.handler)
    testImplementation(libs.netty.codec)
    testImplementation(libs.gson)
}
