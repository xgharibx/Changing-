rootProject.name = "wadeh-android"
pluginManagement { repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
include(":app", ":core")
project(":core").projectDir = file("../core")
