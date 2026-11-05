plugins {
    id("dev.kikugie.stonecutter")
    id("dev.architectury.loom") version "1.17.493" apply false
    id("dev.architectury.loom-no-remap") version "1.17.493" apply false
}

stonecutter active "26.3-fabric"

stonecutter parameters {
    // Node names are "<minecraft>-<loader>": expose the loader as a constant for //? if fabric
    constants.match(node.metadata.project.substringAfterLast('-'), "fabric", "neoforge", "forge")
}
