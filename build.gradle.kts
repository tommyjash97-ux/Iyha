plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = "net.iyha"
version = "0.1.0"

// Paper API version. Override with: gradle build -PpaperApi=26.2.build.+
val paperApi = providers.gradleProperty("paperApi").orElse("26.1.2.build.+")

// Vault and PlaceholderAPI support is compiled in unless the build is run with -PnoIntegrations
// (the GitHub workflow retries that way automatically if their repositories are unreachable).
val integrations = !project.hasProperty("noIntegrations")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    if (integrations) {
        maven("https://jitpack.io")
        maven("https://repo.helpch.at/releases/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${paperApi.get()}")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    if (integrations) {
        compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
        compileOnly("me.clip:placeholderapi:2.11.6") { isTransitive = false }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// FLAT LAYOUT: all source and resource files sit in the repository root so they can be
// uploaded from a phone without folders. This is temporary and will be reorganised later.
sourceSets["main"].java.setSrcDirs(listOf("."))
sourceSets["main"].java.exclude("build/**", ".gradle/**")
if (!integrations) {
    sourceSets["main"].java.exclude("VaultHook.java", "PapiHook.java")
}
sourceSets["main"].resources.setSrcDirs(listOf("."))
sourceSets["main"].resources.include("plugin.yml", "config.yml", "messages.yml")
sourceSets["main"].resources.exclude("build/**", ".gradle/**")

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    shadowJar {
        archiveBaseName.set("IYHACore")
        archiveClassifier.set("")
        relocate("com.zaxxer.hikari", "net.iyha.core.libs.hikari")
        mergeServiceFiles()
        dependencies {
            exclude(dependency("org.slf4j:.*"))
        }
    }
    jar {
        archiveClassifier.set("plain")
    }
    build {
        dependsOn(shadowJar)
    }
}
