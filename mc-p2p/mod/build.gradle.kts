plugins {
    id("fabric-loom") version "1.17.20"
    `java`
}

version = "1.0.0"
group = "io.ezp"

base {
    archivesName.set("ezp-mod")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.4")
    mappings("net.fabricmc:yarn:1.21.4+build.8:v2")
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.119.4+1.21.4")

    // 命令用：brigadier 随 MC 运行时已内置，这里仅用于编译期
    compileOnly("com.mojang:brigadier:1.3.10")
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.jar {}