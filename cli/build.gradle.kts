plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":domain"))
}

application {
    mainClass.set("hypogeum.cli.MainKt")
}

tasks.named<CreateStartScripts>("startScripts") {
    defaultJvmOpts = listOf("-Dstdout.encoding=UTF-8", "-Dstdin.encoding=UTF-8")
}
