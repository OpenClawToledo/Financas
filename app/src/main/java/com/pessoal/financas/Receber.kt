package com.pessoal.financas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import java.io.File

/**
 * Recebe uma fatura partilhada de outro app (PDF, foto, print ou texto de email) ou escolhida no Finanças,
 * lê tudo no próprio celular e mostra a folha de confirmação (index.html#fatura).
 */
class FaturaActivity : Activity(), Hospedeiro {
    private lateinit var web: WebView
    private var resultado: String? = null
    private var pronta = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        web = Tela.criar(this, this, "file:///android_asset/index.html#fatura", true) { pronta = true; entregar() }
        setContentView(web)
        val pedido = intent
        Thread {
            val r = try { LeituraFatura.ler(applicationContext, pedido) }
            catch (e: ErroLeitura) { JSONObject().put("erro", e.message) }
            catch (e: Throwable) { JSONObject().put("erro", "Não consegui ler este arquivo (${e.javaClass.simpleName})") }
            runOnUiThread { resultado = r.toString(); entregar() }
        }.start()
    }

    private fun entregar() {
        val r = resultado ?: return
        if (pronta) web.evaluateJavascript("window.mostrarFatura && mostrarFatura($r)", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.voltar ? voltar() : false") { r -> if (r != "true") finish() }
    }

    override fun vibrar() = runOnUiThread { web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    override fun fechar() = runOnUiThread { finish(); overridePendingTransition(0, 0) }
    override fun avisar(msg: String) = runOnUiThread { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    override fun js(codigo: String) = runOnUiThread { web.evaluateJavascript(codigo, null) }
    override fun ativarNotificacao() {}
    override fun ativarAgitar(ativo: Boolean) {}
    override fun compartilhar(texto: String) {}
    override fun liberarBateria() {}
    override fun permitirInstalacao() {}
    override fun abrirInicioAutomatico() {}
    override fun abrirAjustesPopup() {}
    override fun abrirAjustesApp() {}
    override fun exportar() {}
    override fun importar() {}
    override fun ouvir() {}
    override fun pararDeOuvir() {}
    override fun escolherFatura() {}
}

class ErroLeitura(msg: String) : Exception(msg)

object LeituraFatura {
    private const val LARGURA = 1600

    fun ler(ctx: Context, i: Intent): JSONObject {
        val texto = StringBuilder()
        val qrs = ArrayList<String>()
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                  else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> i.data
        }
        var tipo = i.type.orEmpty()
        if (uri != null) {
            if (tipo.isEmpty() || tipo == "*/*" || tipo == "application/octet-stream") tipo = ctx.contentResolver.getType(uri).orEmpty()
            val ehPdf = tipo == "application/pdf" || (uri.lastPathSegment ?: "").endsWith(".pdf", true)
            when {
                ehPdf -> lerPdf(ctx, uri, texto, qrs)
                tipo.startsWith("image/") -> lerImagem(ctx, uri, texto, qrs)
                tipo.startsWith("text/") -> ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { texto.append(it.readText().take(200_000)) }
            }
        }
        // texto do email (assunto e corpo) quando se partilha texto
        listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .forEach { texto.append("\n").append(it) }
        if (texto.isBlank() && qrs.isEmpty()) throw ErroLeitura("Não encontrei texto nem código QR neste arquivo")
        val f = LeitorFatura.analisar(texto.toString(), qrs)
        return JSONObject()
            .put("emissor", f.emissor)
            .put("valor", f.valor ?: JSONObject.NULL)
            .put("dataLimite", f.dataLimite?.toString() ?: "")
            .put("dataEmissao", f.dataEmissao?.toString() ?: "")
            .put("entidade", f.entidade).put("referencia", f.referencia)
            .put("periodoIni", f.periodoIni?.toString() ?: "").put("periodoFim", f.periodoFim?.toString() ?: "")
            .put("pm", f.pm).put("parcela", f.parcela).put("totalParcelas", f.totalParcelas)
            .put("debitoDireto", f.debitoDireto).put("categoria", f.categoria).put("variavel", f.variavel)
            .put("nif", f.nif).put("origemValor", f.origemValor).put("qr", qrs.isNotEmpty())
    }

    /** Copia para um arquivo temporário: o Gmail e outros só dão acesso ao arquivo por pouco tempo. */
    private fun copiar(ctx: Context, uri: Uri, nome: String): File {
        val f = File(ctx.cacheDir, nome)
        ctx.contentResolver.openInputStream(uri)?.use { e -> f.outputStream().use { e.copyTo(it) } } ?: throw ErroLeitura("Não consegui abrir o arquivo")
        return f
    }

    private fun lerPdf(ctx: Context, uri: Uri, texto: StringBuilder, qrs: MutableList<String>) {
        val arq = copiar(ctx, uri, "fatura.pdf")
        try {
            PDFBoxResourceLoader.init(ctx)
            try {
                PDDocument.load(arq).use { doc ->
                    val st = PDFTextStripper().apply { startPage = 1; endPage = minOf(doc.numberOfPages, 4); sortByPosition = true }
                    texto.append(st.getText(doc))
                }
            } catch (e: InvalidPasswordException) {
                throw ErroLeitura("Este PDF tem palavra-passe. Abra-o, tire um print e partilhe a imagem com o Finanças.")
            } catch (e: ErroLeitura) { throw e } catch (e: Throwable) { /* sem camada de texto: vai por OCR */ }

            val poucoTexto = texto.trim().length < 80
            ParcelFileDescriptor.open(arq, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (p in 0 until minOf(r.pageCount, if (poucoTexto) 2 else 1)) {
                        r.openPage(p).use { pg ->
                            val alt = (LARGURA.toFloat() * pg.height / pg.width).toInt().coerceAtMost(LARGURA * 2)
                            val bmp = Bitmap.createBitmap(LARGURA, alt, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            qrs += lerQrs(bmp, 0)
                            if (poucoTexto) texto.append("\n").append(ocr(bmp, 0))
                            bmp.recycle()
                        }
                    }
                }
            }
        } finally { arq.delete() }
    }

    private fun lerImagem(ctx: Context, uri: Uri, texto: StringBuilder, qrs: MutableList<String>) {
        val arq = copiar(ctx, uri, "fatura.img")
        try {
            val lim = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(arq.path, lim)
            var amostra = 1
            while (maxOf(lim.outWidth, lim.outHeight) / amostra > 2600) amostra *= 2
            val bmp = BitmapFactory.decodeFile(arq.path, BitmapFactory.Options().apply { inSampleSize = amostra })
                ?: throw ErroLeitura("Não consegui abrir a imagem")
            val rot = try {
                when (ExifInterface(arq.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (e: Exception) { 0 }
            qrs += lerQrs(bmp, rot)
            texto.append(ocr(bmp, rot))
            bmp.recycle()
        } finally { arq.delete() }
    }

    /** Códigos QR na imagem (o da fatura portuguesa traz NIF, data e total). */
    private fun lerQrs(bmp: Bitmap, rot: Int): List<String> = try {
        val sc = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        Tasks.await(sc.process(InputImage.fromBitmap(bmp, rot))).mapNotNull { it.rawValue }.also { sc.close() }
    } catch (e: Exception) { emptyList() }

    /** Texto da imagem, reconhecido no próprio celular. */
    private fun ocr(bmp: Bitmap, rot: Int): String = try {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Tasks.await(rec.process(InputImage.fromBitmap(bmp, rot))).text.also { rec.close() }
    } catch (e: Exception) { "" }
}
