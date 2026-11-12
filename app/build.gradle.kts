import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// O Google Services só entra quando o dono coloca app/google-services.json; sem ele o app compila e roda sem conta.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "app.hunter.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hunter.companion"
        minSdk = 26
        targetSdk = 36
        // No CI, versão e código vêm do release.yml (série de version.txt + contador: 0.3.1 → 301); local é série.0.
        versionCode = (project.findProperty("rumo.versionCode") as String?)?.toIntOrNull() ?: 99
        versionName = (project.findProperty("rumo.versionName") as String?)
            ?: (rootProject.file("version.txt").readText().trim() + ".0")
        // O MapLibre traz bibliotecas nativas por arquitetura; celulares atuais são arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
        // Camada de trânsito: endereço do servidor intermediário e token do app (vêm do CI; vazios = sem camada).
        fun quoted(name: String) = "\"" + ((project.findProperty(name) as String?) ?: "").trim().replace("\"", "") + "\""
        buildConfigField("String", "TRAFFIC_TILE_URL", quoted("trafficTileUrl"))
        buildConfigField("String", "TRAFFIC_TOKEN", quoted("trafficToken"))
        // Rolê por perto: endereço do Worker nearby (vem do CI; vazio = recurso desligado).
        buildConfigField("String", "NEARBY_URL", quoted("nearbyUrl"))
    }

    // Chave fixa de release (vem do CI ou de variáveis de ambiente). Sem ela cai na chave de debug.
    val rumoKeystore = System.getenv("RUMO_KEYSTORE")
    signingConfigs {
        if (rumoKeystore != null) {
            create("rumo") {
                storeFile = file(rumoKeystore)
                storePassword = System.getenv("RUMO_STORE_PASS")
                keyAlias = "rumo"
                keyPassword = System.getenv("RUMO_STORE_PASS")
            }
        }
    }

    buildTypes {
        // Release sem debug: o Compose roda várias vezes mais rápido que no build de debug (que era o que travava).
        // Assinado com a chave de debug só para instalar direto no celular.
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName(if (rumoKeystore != null) "rumo" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":pod-protocol"))
    implementation(project(":pod-ble"))
    implementation(project(":navigation"))
    implementation(libs.maplibre)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":trips"))
    implementation(project(":maintenance"))
    implementation(project(":sync-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Só para Migration2To3Test (JVM puro): SQLite real em memória para exercitar o SQL da migração.
    testImplementation(libs.sqlite.jdbc)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
