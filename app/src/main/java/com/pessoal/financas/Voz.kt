package com.pessoal.financas

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject

/**
 * Lançar por voz: usa o reconhecimento de fala do Android (o do Google, que vem no Xiaomi) e
 * vai mandando o texto para a interface por window.aoOuvir({estado, texto, msg}).
 * estado: "ouvindo" · "parcial" (enquanto fala) · "final" · "fim" (sem resultado) · "erro".
 */
class Voz(private val act: Activity, private val js: (String) -> Unit) : RecognitionListener {
    private var rec: SpeechRecognizer? = null

    companion object {
        const val PEDIDO_MICROFONE = 12
        const val PEDIDO_DIALOGO = 13
    }

    private fun intencao() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.packageName)

    fun iniciar() = act.runOnUiThread {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
            return@runOnUiThread
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) { usarDialogo(); return@runOnUiThread }
        try {
            rec?.destroy()
            rec = SpeechRecognizer.createSpeechRecognizer(act).also {
                it.setRecognitionListener(this)
                it.startListening(intencao())
            }
        } catch (e: Exception) { usarDialogo() }
    }

    fun parar() = act.runOnUiThread { rec?.stopListening() }

    fun liberar() = act.runOnUiThread { rec?.destroy(); rec = null }

    /** Sem serviço de fala embutido: abre a janela de voz do Google. */
    private fun usarDialogo() {
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(intencao().putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o valor e o que foi"), PEDIDO_DIALOGO)
        } catch (e: Exception) {
            enviar("erro", msg = "Reconhecimento de voz indisponível. Instale ou ative o app Google.")
        }
    }

    fun resultadoDialogo(ok: Boolean, dados: Intent?) {
        val t = if (ok) dados?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
        if (t.isNullOrBlank()) enviar("fim") else enviar("final", t)
    }

    fun permissao(concedida: Boolean) {
        if (concedida) iniciar() else enviar("erro", msg = "Permita o microfone para lançar por voz")
    }

    private fun enviar(estado: String, texto: String = "", msg: String = "") {
        js("window.aoOuvir && aoOuvir(" + JSONObject().put("estado", estado).put("texto", texto).put("msg", msg) + ")")
    }

    private fun primeiro(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    override fun onReadyForSpeech(params: Bundle?) = enviar("ouvindo")
    override fun onPartialResults(partialResults: Bundle?) { primeiro(partialResults).takeIf { it.isNotBlank() }?.let { enviar("parcial", it) } }
    override fun onResults(results: Bundle?) { primeiro(results).let { if (it.isBlank()) enviar("fim") else enviar("final", it) } }
    override fun onError(error: Int) {
        val msg = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não percebi. Toque no microfone e diga, por exemplo: 5,50 café"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "Sem internet para reconhecer a voz"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permita o microfone para lançar por voz"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O microfone está ocupado. Tente de novo."
            SpeechRecognizer.ERROR_CLIENT -> ""
            else -> "Falha no reconhecimento de voz ($error)"
        }
        enviar("erro", msg = msg)
    }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
