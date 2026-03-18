val jacksonVersion = "2.21.1"

plugins {
    kotlin("jvm") version "2.3.0"
}

val junitJupiterVersion = "6.0.1"
val rapidsAndRiversVersion = "2026012807431769582626.d6f80c5a169d"
val tbdLibsVersion = "2026.01.28-07.21-5436e475"
val ktorVersion = "3.4.0"
val wiremockVersion = "3.13.2"

allprojects {
    group = "no.nav.helse.sparkel"
    version = properties["version"] ?: "local-build"

    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    // Sett opp repositories basert på om vi kjører i CI eller ikke
    // Jf. https://github.com/navikt/utvikling/blob/main/docs/teknisk/Konsumere%20biblioteker%20fra%20Github%20Package%20Registry.md
    repositories {
        mavenCentral()
        if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
            maven {
                url = uri("https://maven.pkg.github.com/navikt/maven-release")
                credentials {
                    username = "token"
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull!!
                }
            }
        } else {
            maven("https://repo.adeo.no/repository/github-package-registry-navikt/")
        }
    }

    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of("21"))
        }
    }

    tasks {
        withType<Test> {
            useJUnitPlatform()
            testLogging {
                events("skipped", "failed")
            }
        }
    }
}

subprojects {
    extra["ktorVersion"] = ktorVersion
    extra["rapidsAndRiversVersion"] = rapidsAndRiversVersion
    extra["tbdLibsVersion"] = tbdLibsVersion

    dependencies {
        constraints {
            api("com.fasterxml.jackson:jackson-bom:$jacksonVersion") {
                because("Alle moduler skal bruke samme versjon av jackson")
            }
        }

        testImplementation("org.wiremock:wiremock:$wiremockVersion") {
            exclude(group = "junit")
        }
        testImplementation("io.ktor:ktor-client-mock-jvm:$ktorVersion")
    }
    tasks {
        if (project.skalLagAppJar()) {
            named<Jar>("jar") {
                archiveBaseName.set("app")

                val mainClass = project.mainClass()

                manifest {
                    attributes["Main-Class"] = mainClass
                    attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(separator = " ") { it.name }
                }

                doLast {
                    val mainClassFound =
                        this.project.sourceSets.findByName("main")?.let {
                            it.output.classesDirs.asFileTree.any { it.path.contains(mainClass.replace(".", File.separator)) }
                        } ?: false

                    if (!mainClassFound) throw RuntimeException("Kunne ikke finne main class: $mainClass")

                    configurations.runtimeClasspath.get().forEach {
                        val file = File("${layout.buildDirectory.get()}/libs/${it.name}")
                        if (!file.exists()) {
                            it.copyTo(file)
                        }
                    }
                }
            }
        }
    }
}

fun Project.mainClass() = "$group.${name.replace("-", "")}.AppKt"

fun Project.skalLagAppJar() = name !in listOf("felles", "infotrygd")
