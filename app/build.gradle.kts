import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

/**
 * Build nativo del decoder software (E7-T12) activado solo con NDK real.
 *
 * Termux no puede ejecutar el NDK oficial (x86_64-only; ADR-0005): el stub
 * en la ruta del SDK no tiene `source.properties` y AGP aborta la
 * configuración de `:app`. El gate detecta un NDK funcional; en CI
 * (ubuntu-latest, NDK preinstalado) se activa y en local se omite, de modo
 * que la batería JVM (ktlint/detekt/test) no dependa del NDK.
 */
fun hayNdkValido(): Boolean {
    val raicesSdk = mutableListOf<String?>()
    raicesSdk.add(System.getenv("ANDROID_HOME"))
    raicesSdk.add(System.getenv("ANDROID_SDK_ROOT"))
    val archivoLocal = rootProject.file("local.properties")
    if (archivoLocal.isFile) {
        archivoLocal.readLines().firstOrNull { it.startsWith("sdk.dir=") }?.let { linea ->
            raicesSdk.add(linea.removePrefix("sdk.dir="))
        }
    }
    return raicesSdk.any { raiz ->
        val ndk = File(raiz, "ndk")
        ndk.isDirectory &&
            (ndk.listFiles() ?: emptyArray())
                .any { sub -> File(sub, "source.properties").isFile }
    }
}

val buildNativo = hayNdkValido()

/**
 * Keystore de producción. El workflow de release lo materializa desde un
 * secreto y pasa la ruta; en local la variable falta y `release` se compila sin
 * firmar, que es lo que permite ensayar el ensamblado sin tener las claves.
 */
val archivoKeystoreRelease: File? =
    System.getenv("ANDROID_KEYSTORE_FILE")?.let(::File)?.takeIf { it.isFile }

/**
 * Versión publicada. El workflow la deriva del tag; sin la variable se mantiene
 * el valor de desarrollo para que `assembleDebug` no dependa del release.
 */
val versionCodePublicada: Int = System.getenv("OTA_VERSION_CODE")?.toIntOrNull() ?: 1
val versionNamePublicada: String = System.getenv("OTA_VERSION_NAME") ?: "0.1.0"

/**
 * Valor de `BuildConfig` a partir de una variable de entorno.
 *
 * El valor se escapa para que un error de configuración no rompa el script de
 * build; si la variable falta, queda vacío y el OTA no verifica nada.
 */
fun cadenaBuildConfig(
    nombre: String,
    porDefecto: String = "",
): String {
    val valor = (System.getenv(nombre) ?: porDefecto).replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$valor\""
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.copernicotv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.copernicotv.app"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodePublicada
        versionName = versionNamePublicada

        // Identidad del canal OTA. En release la inyecta el workflow; en debug
        // quedan vacías y el comprobador falla cerrado (no hay actualizaciones
        // sin firma verificable).
        buildConfigField("String", "OTA_REPOSITORIO", cadenaBuildConfig("OTA_REPOSITORIO", "guigerdts/CoperNicoTV"))
        buildConfigField("String", "OTA_MANIFIESTO_PUBKEY", cadenaBuildConfig("OTA_MANIFIESTO_PUBKEY"))
        buildConfigField("String", "OTA_CERT_SHA256", cadenaBuildConfig("OTA_CERT_SHA256"))

        testInstrumentationRunner = "com.copernicotv.app.HiltTestRunner"

        if (buildNativo) {
            ndk {
                // ABIs del APK con el decoder software (E7-T12); x86_64 cubre
                // los emuladores de la CI instrumentada.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    signingConfigs {
        if (archivoKeystoreRelease != null) {
            create("release") {
                storeFile = archivoKeystoreRelease
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sin keystore configurado queda nulo y el APK se ensambla sin
            // firmar: el workflow de release es el único que publica.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (buildNativo) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        // La medición JVM de búsqueda reutiliza la misma fixture versionada de
        // `:core`; no se crea una tercera copia con hash potencialmente distinto.
        getByName("test").resources.srcDir(rootProject.file("core/src/test/resources"))
    }
}

/**
 * Medición P1 de E3-T1 (DC-10): primera página de búsqueda y página 2.
 *
 * Se activa con `-Pp1.busqueda=true` sobre `:app:testDebugUnitTest`; sin esa
 * propiedad la tarea conserva su comportamiento funcional y su heap habitual.
 * La medición necesita `-Xmx512m`, filtra solo el medidor y publica evidencia
 * en `app/build/p1-busqueda/` para el workflow `p1-performance`.
 */
tasks.withType<Test>().configureEach {
    // Robolectric carga el provider TLS de Android (Conscrypt), que accede por
    // reflexión a `java.net.InetAddress.holder()`. Con JDK 17 la encapsulación
    // fuerte de JPMS lo bloquea (`does not "opens java.net"`), el handshake
    // TLS del servidor de prueba muere después del intercambio de certificados
    // y el listener se cierra antes de servir la respuesta. Solo afecta a las
    // pruebas JVM: en Android real no hay JPMS.
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
    if (name == "testDebugUnitTest" && providers.gradleProperty("p1.busqueda").isPresent) {
        maxHeapSize = "512m"
        systemProperty("p1.medir", "true")
        systemProperty("p1.commit", providers.gradleProperty("p1.commit").getOrElse("desconocido"))
        systemProperty("repoRoot", rootProject.projectDir.absolutePath)
        filter {
            includeTestsMatching("com.copernicotv.app.p1.MedicionP1BusquedaTest")
        }
        outputs.upToDateWhen { false }
    } else if (name == "testDebugUnitTest") {
        // La importación masiva de E7-T7 ronda 8 (146.762 canales) necesita
        // más heap que el predeterminado de Gradle (512m).
        maxHeapSize = "1536m"
        // El 147k de P0 instruye avance por fase; sin esto los `println` solo
        // quedan en el XML y no se ve dónde se detiene la prueba en CI.
        testLogging {
            events("started", "passed", "failed")
            showStandardStreams = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("detekt.yml"))
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.compiler.androidx)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.decoder)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.ui)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.work.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.test.manifest)
}
