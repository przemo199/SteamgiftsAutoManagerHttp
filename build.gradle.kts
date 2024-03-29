plugins {
    java
    application
    id("io.freefair.lombok") version "8.4"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    implementation("org.jsoup:jsoup:1.16.2")
}

application {
    mainClass.set("steamgiftsautomanager.SteamgiftsAutoManager")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "steamgiftsautomanager.SteamgiftsAutoManager")
    }

    from(
        configurations.runtimeClasspath.get().filter { it.exists() }
            .map { if (it.isDirectory) it else zipTree(it) },
    )
}

tasks.register(name = "runTool", type = Exec::class) {
    workingDir = file(".")
    commandLine("java", "-jar", "./build/libs/steamgifts-auto-manager-http.jar")
}
