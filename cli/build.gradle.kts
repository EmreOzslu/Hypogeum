plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
}

application {
    mainClass.set("hypogeum.cli.MainKt")
}

tasks.named<CreateStartScripts>("startScripts") {
    defaultJvmOpts = listOf("-Dstdout.encoding=UTF-8", "-Dstdin.encoding=UTF-8")
}
