plugins {
    id("bvc.java-base")
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(17)
}
