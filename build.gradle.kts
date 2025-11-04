plugins {
    kotlin("jvm")
    id("net.minecraftforge.gradle") version "5.1.+"
    id("com.modrinth.minotaur") version "2.+"
    `maven-publish`
    id("com.matthewprenger.cursegradle") version "1.4.0"
}

// Current KFF version
val kffVersion = "3.13.0"
val kffMaxVersion = "4.0.0"
val kffGroup = "xyz.bluspring"

allprojects {
    version = kffVersion
    group = kffGroup
}

evaluationDependsOnChildren()

val mc_version: String by project
val forge_version: String by project

val coroutines_version: String by project
val serialization_version: String by project
val datetime_version: String by project
val atomicfu_version: String by project
val io_version: String by project

val shadow: Configuration by configurations.creating {
    exclude("org.jetbrains", "annotations")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

jarJar.enable()

configurations {
    apiElements {
        artifacts.clear()
    }
    runtimeElements {
        setExtendsFrom(emptySet())
        // Publish the jarJar
        artifacts.clear()
        outgoing.artifact(tasks.jarJar)
    }
    minecraftLibrary {
        extendsFrom(shadow)
    }
}

minecraft {
    mappings("official", mc_version)

    runs {
        create("client") {
            workingDirectory(project.file("run"))

            property("forge.logging.markers", "SCAN,LOADING,CORE")
            property("forge.logging.console.level", "debug")
        }

        create("server") {
            workingDirectory(project.file("run/server"))

            property("forge.logging.markers", "SCAN,LOADING,CORE")
            property("forge.logging.console.level", "debug")
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("net.minecraftforge:forge:$mc_version-$forge_version")

    shadow("org.jetbrains.kotlin:kotlin-reflect:${kotlin.coreLibrariesVersion}")
    shadow("org.jetbrains.kotlin:kotlin-stdlib:${kotlin.coreLibrariesVersion}")
    shadow("org.jetbrains.kotlin:kotlin-stdlib-common:${kotlin.coreLibrariesVersion}")
    shadow("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutines_version}")
    shadow("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${coroutines_version}")
    shadow("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${coroutines_version}")
    shadow("org.jetbrains.kotlinx:kotlinx-serialization-core:${serialization_version}")
    shadow("org.jetbrains.kotlinx:kotlinx-serialization-json:${serialization_version}")
    shadow("org.jetbrains.kotlinx:kotlinx-datetime-jvm:${datetime_version}")
    shadow("org.jetbrains.kotlinx:atomicfu-jvm:${atomicfu_version}")
    shadow("org.jetbrains.kotlinx:kotlinx-io-core-jvm:${io_version}")
    shadow("org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:${io_version}")

    // KFF Modules
    implementation(include(project(":kfflang"), kffMaxVersion))
    implementation(include(project(":kfflib"), kffMaxVersion))
    implementation(include(project(":kffmod"), kffMaxVersion))
}

kotlin {
    jvmToolchain(17)
}

tasks {
    jar {
        enabled = false
    }

    jarJar.configure {
        from(provider { shadow.map(::zipTree).toTypedArray() })
        manifest {
            attributes(
                "Automatic-Module-Name" to "thedarkcolour.kotlinforforge",
                "FMLModType" to "LIBRARY"
            )
        }
    }

    whenTaskAdded {
        // Disable reobfJar
        if (name == "reobfJar") {
            enabled = false
        }
        // Fight ForgeGradle and Forge crashing when MOD_CLASSES don't exist
        if (name == "prepareRuns") {
            doFirst {
                sourceSets.main.get().output.files.forEach(File::mkdirs)
            }
        }
    }

    assemble {
        dependsOn(jarJar)
    }
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            suppressAllPomMetadataWarnings() // Shush
            from(components["java"])
        }
    }
}

subprojects {
    publishing {
        publications {
            repositories {
                maven("https://mvn.devos.one/releases") {
                    name = "devOS"

                    credentials {
                        username = System.getenv()["MAVEN_USER"]
                        password = System.getenv()["MAVEN_PASS"]
                    }
                }
            }
        }
    }
}

fun DependencyHandler.minecraft(
    dependencyNotation: Any
): Dependency? = add("minecraft", dependencyNotation)

fun DependencyHandler.library(
    dependencyNotation: Any
): Dependency? = add("library", dependencyNotation)

val supportedMcVersions = listOf("1.18", "1.18.1", "1.18.2", "1.19", "1.19.1", "1.19.2")

curseforge {
    // Use the command line on Linux because IntelliJ doesn't pick up from .bashrc
    apiKey = System.getenv("CURSEFORGE_API_KEY") ?: "no-publishing-allowed"

    project(closureOf<com.matthewprenger.cursegradle.CurseProject> {
        id = "351264"
        releaseType = "release"
        gameVersionStrings.add("Forge")
        gameVersionStrings.add("Java 17")
        gameVersionStrings.addAll(supportedMcVersions)

        // from Modrinth's Util.resolveFile
        @Suppress("DEPRECATION")
        mainArtifact(tasks.jarJar.get().archivePath, closureOf<com.matthewprenger.cursegradle.CurseArtifact> {
            displayName = "Kotlin for Forge ${project.version}"
        })
    })
}

modrinth {
    projectId.set("ordsPcFz")
    versionName.set("Kotlin for Forge ${project.version}")
    versionNumber.set("${project.version}")
    versionType.set("release")
    gameVersions.addAll(supportedMcVersions)
    loaders.add("forge")
    uploadFile.provider(tasks.jarJar)
}

// maven.repo.local is set within the Julia script in the website branch
tasks.create("publishAllMavens") {
    for (proj in arrayOf(":", ":kfflib", ":kfflang", ":kffmod")) {
        finalizedBy(project(proj).tasks.getByName("publishToMavenLocal"))
    }
}
tasks.create("publishModPlatforms") {
    println("Publishing Kotlin for Forge $kffVersion to Modrinth and CurseForge")
    finalizedBy(tasks.modrinth)
    finalizedBy(tasks.curseforge)
}

fun DependencyHandler.include(dep: ModuleDependency, maxVersion: String? = null): ModuleDependency {
    api(dep) // Add module metadata compileOnly dependency
    jarJar(dep.copy()) {
        isTransitive = false
        jarJar.pin(this, version)
        if (maxVersion != null) {
            jarJar.ranged(this, "[$version,$maxVersion)")
        }
    }
    return dep
}

// Kotlin function ambiguity fix
fun <T> Property<T>.provider(value: T) {
    set(value)
}
