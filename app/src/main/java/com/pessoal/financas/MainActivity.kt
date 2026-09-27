package com.pessoal.financas

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.webkit.WebView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A interface é uma página HTML local (assets/index.html) com ponte para os dados nativos. */
class MainActivity : Activity(), Hospedeiro {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fundo = Color.parseColor("#15131F")
        window.statusBarColor = fundo
        window.navigationBarColor = fundo
        web = Tela.criar(this, this, "file:///android_asset/index.html", false)
        setContentView(web)
        Lembretes.agendar(this)
        Nuvem.aoMudar(recarregarTela)
        tratarPedidoAtualizar(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratarPedidoAtualizar(intent)
    }

    /** Veio da notificação "Nova versão": instala já. */
    private fun tratarPedidoAtualizar(i: Intent?) {
        if (i?.getBooleanExtra("atualizar", false) != true) return
        i.removeExtra("atualizar")
        if (!Atualizador.podeInstalar(this)) { permitirInstalacao(); return }
        Nuvem.executar { Atualizador.baixarEInstalar(this); js("window.recarregar && recarregar()") }
    }

    /** Quando a sincronização traz novidades, a tela recarrega sozinha. */
    private val recarregarTela: () -> Unit = { js("window.recarregar && recarregar()") }

    override fun onDestroy() {
        Nuvem.remover(recarregarTela)
        super.onDestroy()
    }

    override fun onPause() {
        Nuvem.telaVisivel = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        Nuvem.telaVisivel = true
        Nuvem.agendar(this, 0)
        Atualizador.verificarEmSegundoPlano(this, telaAberta = true)
        if (Ajustes.agitarAtivo(this)) Agitar.sincronizar(this)
        else if (Ajustes.notifAtiva(this)) Notificacao.mostrar(this)
        if (::web.isInitialized) web.evaluateJavascript("window.recarregar && recarregar()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.voltar ? voltar() : false") { r -> if (r != "true") finish() }
    }

    // ---------- Hospedeiro ----------
    override fun vibrar() = runOnUiThread { web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    override fun fechar() {}
    override fun js(codigo: String) = runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(codigo, null) }
    override fun compartilhar(texto: String) = runOnUiThread {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto)
        startActivity(Intent.createChooser(i, "Enviar convite"))
    }
    override fun avisar(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private var pedidoAgitar = false

    private fun temPermissao() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun ativarNotificacao() = runOnUiThread {
        if (Ajustes.notifAtiva(this) && !Ajustes.agitarAtivo(this)) {
            // desligar
            Ajustes.definirNotifAtiva(this, false)
            getSystemService(android.app.NotificationManager::class.java).cancel(Notificacao.ID)
            web.evaluateJavascript("window.recarregar && recarregar()", null)
        } else if (!temPermissao()) {
            pedidoAgitar = false
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        } else ligarNotificacao()
    }

    override fun ativarAgitar(ativo: Boolean) = runOnUiThread {
        if (!ativo) {
            Ajustes.definirAgitar(this, false)
            Agitar.sincronizar(this)
            web.evaluateJavascript("window.recarregar && recarregar()", null)
        } else if (!temPermissao()) {
            pedidoAgitar = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        } else ligarAgitar()
    }

    private fun ligarAgitar() {
        Ajustes.definirAgitar(this, true)
        Ajustes.definirNotifAtiva(this, true)
        Agitar.sincronizar(this)
        web.evaluateJavascript("window.recarregar && recarregar()", null)
    }

    override fun abrirAjustesPopup() = runOnUiThread {
        Notificacao.criarCanais(this)
        val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notificacao.CANAL_POPUP)
        try { startActivity(i) } catch (e: Exception) { abrirAjustesApp() }
    }

    /** Liga "Instalar apps desconhecidos" para o próprio Finanças (necessário para se atualizar). */
    override fun permitirInstalacao() = runOnUiThread {
        try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { abrirAjustesApp() }
    }

    override fun liberarBateria() = runOnUiThread {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) { abrirAjustesApp() }
    }

    /** Tela de "Início automático" do HyperOS/MIUI; noutros celulares, os detalhes do app. */
    override fun abrirInicioAutomatico() = runOnUiThread {
        val tentativas = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        )
        for (i in tentativas) {
            try { startActivity(i); return@runOnUiThread } catch (e: Exception) {}
        }
        abrirAjustesApp()
    }

    override fun abrirAjustesApp() = runOnUiThread {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun exportar() = runOnUiThread {
        val nome = "financas-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, nome)
        startActivityForResult(i, EXPORTAR)
    }

    override fun importar() = runOnUiThread {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, IMPORTAR)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                EXPORTAR -> {
                    contentResolver.openOutputStream(uri)?.use { it.write(Backup.exportar(this).toByteArray()) }
                    avisar("Backup salvo")
                }
                IMPORTAR -> {
                    val texto = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    if (Backup.importar(this, texto)) {
                        avisar("Backup restaurado")
                        web.evaluateJavascript("window.recarregar && recarregar()", null)
                        if (Ajustes.agitarAtivo(this)) Agitar.sincronizar(this)
                        else if (Ajustes.notifAtiva(this)) Notificacao.mostrar(this)
                    } else avisar("Esse arquivo não é um backup do Finanças")
                }
            }
        } catch (e: Exception) {
            avisar("Não foi possível acessar o arquivo")
        }
    }

    private fun ligarNotificacao() {
        Ajustes.definirNotifAtiva(this, true)
        Notificacao.mostrar(this)
        web.evaluateJavascript("window.recarregar && recarregar()", null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 10) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (pedidoAgitar) ligarAgitar() else ligarNotificacao() }
        else avisar("Sem permissão de notificação")
    }

    companion object {
        private const val EXPORTAR = 31
        private const val IMPORTAR = 32
    }
}
