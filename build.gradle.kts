plugins {
    kotlin("jvm") version "1.9.20-Beta2"
    application
}

group = "com.storyteller_f"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation ("org.apache.lucene:lucene-core:8.2.0")
    implementation ("org.apache.lucene:lucene-analyzers-common:8.2.0")
    implementation ("org.apache.lucene:lucene-queryparser:8.2.0")
    implementation("com.j256.simplemagic:simplemagic:1.17")
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.6")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt")
}