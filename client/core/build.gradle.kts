plugins {
    id("bvc.java8")
}

description = "Minecraft-independent client core (network, codec, mixing)"

dependencies {
    api(project(":protocol"))
    implementation(libs.concentus)

    // Integration tests run the real proxy core in-process.
    testImplementation(project(":proxy:common"))
    testImplementation(libs.netty.handler)
    testImplementation(libs.netty.codec)
    // Provided by Velocity / BungeeCord at runtime; the proxy's web panel uses it.
    testImplementation(libs.gson)
}
