rootProject.name = "wadeh"
pluginManagement { repositories { gradlePluginPortal(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); google() } }
include(":core", ":server")
