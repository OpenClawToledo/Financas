package com.pessoal.financas

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Toast

/** Bloco "Gasto" da Central de controle. */
class BlocoRapido : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Gasto"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, LancamentoRapidoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Sobreposição transparente que mostra só a folha "Novo gasto", com o mesmo visual do app. */
class LancamentoRapidoActivity : Activity(), Hospedeiro {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = Tela.criar(this, this, "file:///android_asset/index.html#rapido", true) { w ->
            // abre o teclado automaticamente
            w.postDelayed({
                w.requestFocus()
                w.evaluateJavascript("window.focarEntrada && focarEntrada()", null)
                getSystemService(InputMethodManager::class.java).showSoftInput(w, InputMethodManager.SHOW_IMPLICIT)
            }, 350)
        }
        setContentView(web)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.voltar ? voltar() : false") { r -> if (r != "true") finish() }
    }

    override fun vibrar() = runOnUiThread { web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    override fun fechar() = runOnUiThread { finish(); overridePendingTransition(0, 0) }
    override fun avisar(msg: String) = runOnUiThread { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    override fun ativarNotificacao() {}
    override fun ativarAgitar(ativo: Boolean) {}
    override fun compartilhar(texto: String) {}
    override fun liberarBateria() {}
    override fun permitirInstalacao() {}
    override fun abrirInicioAutomatico() {}
    override fun js(codigo: String) = runOnUiThread { web.evaluateJavascript(codigo, null) }
    override fun abrirAjustesPopup() {}
    override fun abrirAjustesApp() {}
    override fun exportar() {}
    override fun importar() {}
}
