plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
application { mainClass.set("eg.wadeh.server.MainKt") }
dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core); implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation); implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.html); implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.status.pages); implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.flyway.core); implementation(libs.flyway.postgres)
    implementation(libs.postgres); implementation(libs.hikari)
    implementation(libs.poi.ooxml); implementation(libs.logback)
    implementation(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}
tasks.test {
    useJUnitPlatform()
    environment("DATABASE_URL", System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/wadeh_test?user=wadeh&password=wadeh_dev")
    environment("APP_ENV", "test")
}
