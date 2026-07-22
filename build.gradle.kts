import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    application
}

repositories {
    mavenCentral()
}

val lwjglVersion = "3.3.6"

val isMac = System.getProperty("os.name").lowercase().contains("mac")

// every platform's natives ship in the jar, so one build runs anywhere
val lwjglNatives = listOf(
    "natives-macos", "natives-macos-arm64",
    "natives-windows", "natives-windows-arm64",
    "natives-linux", "natives-linux-arm64"
)

dependencies {
    implementation("org.joml:joml:1.10.9")
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    for (module in listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-tinyfd")) {
        implementation("org.lwjgl:$module")
        for (platform in lwjglNatives) runtimeOnly("org.lwjgl:$module::$platform")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

application {
    mainClass = "depgraph.MainKt"
    applicationDefaultJvmArgs = buildList {
        add("-Xmx3g")
        add("--enable-native-access=ALL-UNNAMED")
        if (isMac) add("-XstartOnFirstThread")
    }
}

tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Single runnable jar with all dependencies and natives for every platform"
    archiveBaseName = "depgraph"
    archiveClassifier = "all"
    manifest {
        attributes["Main-Class"] = "depgraph.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
}
