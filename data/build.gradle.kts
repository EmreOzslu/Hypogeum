plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Bytecode 1.8: dx (Android dex) invokedynamic desteklemediği için APK bu modüllere ihtiyaç duyar.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        // dx (Android dex) invokedynamic'i desteklemez: lambda ve SAM dönüşümleri sınıf olarak üretilir.
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
