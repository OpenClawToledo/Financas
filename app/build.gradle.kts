plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pessoal.financas"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pessoal.financas"
        minSdk = 29
        targetSdk = 34
        // Cada build no GitHub ganha um número maior, permitindo atualizar por cima
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        // Versão mostrada no app; o número depois do ponto é a compilação do GitHub
        versionName = "1.9 (" + (System.getenv("GITHUB_RUN_NUMBER") ?: "0") + ")"
    }

    // Chave fixa: sem ela, cada build teria assinatura diferente e não atualizaria por cima
    signingConfigs {
        getByName("debug") {
            storeFile = file("../chave/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") } }
}

dependencies {
    // Ler faturas no próprio celular: texto de PDFs, texto de fotos (OCR) e o código QR das faturas
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
}
