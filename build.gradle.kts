plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.storetrack"
version = property("pluginVersion") as String

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.google.code.gson:gson:2.10.1") // shipped unshaded by Paper, compileOnly is enough
    compileOnly("me.clip:placeholderapi:2.12.3")
    // FancyNpcs is called entirely through reflection (see NpcSkins.java) - its current release
    // is built for a newer JDK than this project's Java 21 toolchain can read as a compile-time
    // dependency (class file major version 69 / Java 25), so there's no jar to compile against.

    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    test {
        useJUnitPlatform()
    }

    shadowJar {
        archiveFileName = "StoreTrack-${project.version}.jar"
        // No relocate() here: this Shadow release's ASM remapper throws on plain LDC/invokedynamic
        // Type constants pointing at our OWN classes (Config$Provider, any record, etc.) the moment
        // any relocation is configured - a real Shadow/ASM compatibility bug, not something fixable
        // from this project's source. Risk this accepts: HikariCP/sqlite-jdbc/mariadb-driver ship
        // unrelocated, so a version clash with another plugin shading the same libraries differently
        // could NoSuchMethodError. None of this server's other plugins currently bundle them.
        minimize()
    }

    build {
        dependsOn(shadowJar)
    }
}
