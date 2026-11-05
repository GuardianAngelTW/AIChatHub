plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
kotlin { jvmToolchain(21) }
val app = rootDir.resolve("../../app/src/main/java/com/aichathub")
sourceSets["main"].kotlin.srcDirs("src/main/kotlin", "build/appsrc")
val copyApp by tasks.registering(Copy::class) {
    from(app) {
        include("data/Models.kt", "data/Presets.kt", "net/*.kt")
    }
    into("build/appsrc/com/aichathub")
}
tasks.named("compileKotlin") { dependsOn(copyApp) }
application { applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8"); mainClass.set(providers.gradleProperty("main").getOrElse("TestMainKt")) }
