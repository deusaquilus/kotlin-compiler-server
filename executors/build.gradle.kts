plugins {
  kotlin("jvm")
}

kotlin.jvmToolchain {
  languageVersion.set(JavaLanguageVersion.of(17))
  vendor.set(JvmVendorSpec.AMAZON)
  //jvmToolchain(8)
}

dependencies {
  implementation(libs.junit)
  //implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<Jar>().getByName("jar") {
  destinationDirectory.set(libJVMFolder)
}
repositories {
  mavenCentral()
}
