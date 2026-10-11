plugins {
    kotlin("jvm")
}

// Android çerçeve sınıfları yalnızca derleme için: API 16 framework jar'ı (android-all, Maven Central).
// scripts/build_apk.py bu jar'ı indirir; ANDROID_JAR ile başka bir yol verilebilir.
val androidJar = providers.environmentVariable("ANDROID_JAR")
    .orElse(layout.buildDirectory.file("tools/android-all-4.1.2.jar").map { it.asFile.absolutePath })

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
    implementation(project(":data"))
    compileOnly(files(androidJar))
}
