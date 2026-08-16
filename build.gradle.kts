plugins {
    java
}

import java.util.zip.ZipFile

group = "dev.ryder"
version = "0.1.0"

base {
    archivesName.set("midiblock")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 1.21.4 is the compile target; production code deliberately stays within the 1.21 API.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        manifest {
            attributes(
                "Implementation-Title" to "MidiBlock",
                "Implementation-Version" to project.version,
                "MidiBlock-Supported-Minecraft" to "1.21.x, 26.1-26.2",
            )
        }
    }
}

tasks.register("verifyReleaseArtifact") {
    group = "verification"
    description = "Checks the release jar metadata, bundled resources, dependency boundaries, and Java 21 bytecode target."
    dependsOn(tasks.jar)

    doLast {
        val jarFile = tasks.jar.get().archiveFile.get().asFile
        ZipFile(jarFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            listOf("plugin.yml", "config.yml", "sound-profiles/1.21.4.yml", "META-INF/MANIFEST.MF").forEach { required ->
                check(required in names) { "Release jar is missing $required" }
            }

            val forbiddenPrefixes = listOf("io/papermc/", "org/bukkit/", "net/kyori/")
            val bundledDependency = names.firstOrNull { name -> forbiddenPrefixes.any(name::startsWith) }
            check(bundledDependency == null) { "Release jar unexpectedly bundles server/API dependency class $bundledDependency" }

            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                zip.getInputStream(entry).use { input ->
                    val header = ByteArray(8)
                    check(input.read(header) == header.size) { "Could not read class header for ${entry.name}" }
                    val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                    check(major <= 65) { "${entry.name} targets classfile major $major, expected Java 21 or lower" }
                }
            }
        }
    }
}

tasks.check {
    dependsOn("verifyReleaseArtifact")
}
