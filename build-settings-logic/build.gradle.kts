plugins {
    `kotlin-dsl`
    kotlin("jvm") version "2.1.20"
}

dependencies {
    //implementation(libs.gradle.develocity)
    //implementation(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
}
kotlin {
    jvmToolchain(17)
}
