plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

base {
    archivesName = properties["archives_base_name"] as String
    version = "1.1.0"
    group = properties["maven_group"] as String
}

repositories {
    maven("https://maven.meteordev.org/releases") { name = "meteor-releases" }
    maven("https://maven.meteordev.org/snapshots") { name = "meteor-snapshots" }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("meteordevelopment:meteor-client:26.2-SNAPSHOT")
}

tasks {
    processResources {
        val values = mapOf(
            "version" to project.version,
            "minecraft_version" to "26.2"
        )
        inputs.properties(values)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") { expand(values) }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}
