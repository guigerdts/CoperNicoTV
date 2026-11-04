plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(17)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("detekt.yml"))
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // La prueba de conformidad Unicode lee los datos oficiales vendorizados.
    systemProperty("repoRoot", rootProject.projectDir.absolutePath)
}

/**
 * Medición P1 de DC-10 en el runner de referencia.
 *
 * Corre aislada de `test` porque necesita un heap acotado (`-Xmx512m`) y no
 * forma parte de la verificación funcional de cada PR. La evidencia se publica
 * como artefacto del workflow `performance`.
 */
val p1Performance by tasks.registering(Test::class) {
    description = "Medición P1 (DC-10) del parseo de 10 000 entradas"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "512m"
    systemProperty("p1.medir", "true")
    systemProperty("p1.commit", providers.gradleProperty("p1.commit").getOrElse("desconocido"))
    systemProperty("repoRoot", rootProject.projectDir.absolutePath)
    filter {
        includeTestsMatching("com.copernicotv.core.m3u.MedicionP1Test")
    }
    outputs.upToDateWhen { false }
}
