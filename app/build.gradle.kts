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
}
