plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven("https://maven.meteordev.org/releases") { name = "meteor-releases" }
    maven("https://maven.meteordev.org/snapshots") { name = "meteor-snapshots" }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)
    modImplementation(libs.meteor.client)
}

tasks {
    processResources {
        val values = mapOf(
            "version" to project.version,
            "minecraft_version" to libs.versions.minecraft.get()
        )
        inputs.properties(values)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") { expand(values) }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}
