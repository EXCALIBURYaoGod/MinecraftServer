plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    java
}

group = "io.petquest"
version = "1.0.0"

base {
    archivesName.set("petquest")
}

// MC 26.1+ 为未混淆版本，直接使用 Mojang 官方命名
loom {
    splitEnvironmentSourceSets()
    mods {
        create("petquest") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.160.0+26.2")
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.jar {}