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
    // Parser XMLTV streaming (ADR-0003): XmlPullParser portable JVM/Android.
    implementation(libs.kxml2)
    implementation(libs.kotlinx.coroutines.core)
    // Manifiesto OTA: solo se usa `parseToJsonElement`, sin plugin de
    // serialización ni clases generadas.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // La prueba de conformidad Unicode lee los datos oficiales vendorizados.
    systemProperty("repoRoot", rootProject.projectDir.absolutePath)
    filter {
        // La prueba grande necesita un heap acotado y su tarea dedicada.
        excludeTestsMatching("com.copernicotv.core.reconciliacion.ReconciliadorGrandeTest")
    }
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

/**
 * Medición P1 de XMLTV para S24/E5-T1.
 *
 * Se separa de [p1Performance] porque el fixture y el objeto medido son
 * distintos. No sustituye la aprobación de un baseline XMLTV: publica la
 * medición cruda para revisión.
 */
val p1XmltvPerformance by tasks.registering(Test::class) {
    description = "Medición P1 de parseo XMLTV de 10 000 programas"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "512m"
    systemProperty("p1.medir", "true")
    systemProperty("p1.commit", providers.gradleProperty("p1.commit").getOrElse("desconocido"))
    systemProperty("repoRoot", rootProject.projectDir.absolutePath)
    filter {
        includeTestsMatching("com.copernicotv.core.xmltv.MedicionP1XmltvTest")
    }
    outputs.upToDateWhen { false }
}

/**
 * Reconciliación de un catálogo de 147 000 canales con `-Xmx256m`.
 *
 * Reproduce el límite de memoria del dispositivo y demuestra que el motor
 * indexado no necesita más heap. Se ejecuta aparte de `test` porque el tamaño
 * del catálogo hace que la prueba sea lenta para el ciclo normal de PR.
 */
val reconciliacionGrande by tasks.registering(Test::class) {
    description = "Reconciliación de 147 000 canales con heap acotado"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "256m"
    systemProperty("repoRoot", rootProject.projectDir.absolutePath)
    filter {
        includeTestsMatching("com.copernicotv.core.reconciliacion.ReconciliadorGrandeTest")
    }
    outputs.upToDateWhen { false }
}
