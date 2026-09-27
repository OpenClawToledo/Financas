package com.pessoal.financas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização automática, sem loja.
 * O GitHub compila e publica no Supabase Storage (pasta pública "atualizacoes"):
 *   versao.json  →  {"codigo": 42, "versao": "1.9 (42)", "apk": "financas-42.apk", "notas": "..."}
 *   financas-42.apk
 * O app compara com a versão instalada, baixa e instala. Depois da primeira atualização feita pelo
 * próprio app, o Android 12+ deixa as seguintes serem instaladas sem pedir confirmação.
 */
object Atualizador {
    private const val PASTA = "/storage/v1/object/public/atualizacoes/"
    private const val CANAL = "atualizacoes"
    private fun p(ctx: Context) = ctx.getSharedPreferences("atualizacoes", Context.MODE_PRIVATE)
    private fun base() = Sessao.URL_PADRAO   // as atualizações vêm sempre do servidor padrão

    fun codigoInstalado(ctx: Context): Long = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) { 0L }

    fun automatico(ctx: Context) = p(ctx).getBoolean("auto", true)
    fun definirAutomatico(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("auto", v).commit() }

    fun estado(ctx: Context): JSONObject {
        val pr = p(ctx)
        val novo = pr.getLong("codigoNovo", 0L)
        return JSONObject()
            .put("disponivel", novo > codigoInstalado(ctx))
            .put("versao", pr.getString("versaoNova", ""))
            .put("notas", pr.getString("notas", ""))
            .put("etapa", pr.getString("etapa", ""))
            .put("erro", pr.getString("erro", ""))
            .put("verificadoMs", pr.getLong("verificadoMs", 0L))
            .put("automatico", automatico(ctx))
            .put("podeInstalar", podeInstalar(ctx))
    }

    private fun etapa(ctx: Context, e: String, erro: String = "") { p(ctx).edit().putString("etapa", e).putString("erro", erro).commit() }

    fun podeInstalar(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Consulta o versao.json. Devolve true se houver versão nova. */
    fun verificar(ctx: Context): Boolean {
        val r = Http.enviar("GET", base() + PASTA + "versao.json?t=" + System.currentTimeMillis(), mapOf("Cache-Control" to "no-cache"))
        p(ctx).edit().putLong("verificadoMs", System.currentTimeMillis()).commit()
        if (!r.ok) {
            etapa(ctx, "", if (r.codigo == 400 || r.codigo == 404) "Ainda não há nenhuma versão publicada" else "Não foi possível verificar agora")
            return false
        }
        val j = r.json()
        val codigo = j.optLong("codigo", 0L)
        p(ctx).edit().putLong("codigoNovo", codigo).putString("versaoNova", j.optString("versao"))
            .putString("apk", j.optString("apk")).putString("notas", j.optString("notas")).commit()
        etapa(ctx, "")
        return codigo > codigoInstalado(ctx)
    }

    /** Verifica no máximo a cada 6 horas (ou sempre, se forcar). Se houver novidade, instala ou avisa. */
    fun verificarEmSegundoPlano(ctx: Context, forcar: Boolean = false, telaAberta: Boolean = false) {
        val app = ctx.applicationContext
        if (!forcar && System.currentTimeMillis() - p(app).getLong("verificadoMs", 0L) < 6 * 3600_000L) return
        Nuvem.executar {
            if (!verificar(app)) return@executar
            when {
                telaAberta && automatico(app) && podeInstalar(app) -> baixarEInstalar(app)
                !telaAberta -> avisar(app)
            }
        }
    }

    /** Baixa o APK e entrega ao instalador do Android. Roda fora da tela. */
    fun baixarEInstalar(ctx: Context): String? {
        val apk = p(ctx).getString("apk", "") ?: ""
        if (apk.isEmpty()) return "Nenhuma versão nova encontrada"
        if (!podeInstalar(ctx)) return "Permita que o Finanças instale apps (Ajustes → Instalar apps desconhecidos)"
        etapa(ctx, "baixando")
        val arquivo = File(ctx.cacheDir, "atualizacao.apk")
        try {
            val c = URL(base() + PASTA + apk).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 60000
            if (c.responseCode !in 200..299) { etapa(ctx, "", "Falha ao baixar (${c.responseCode})"); return "Falha ao baixar" }
            c.inputStream.use { entrada -> arquivo.outputStream().use { entrada.copyTo(it) } }
            c.disconnect()
        } catch (e: Exception) {
            etapa(ctx, "", "Falha ao baixar: " + motivoFalha(e.javaClass.simpleName + ": " + e.message)); return "Falha ao baixar"
        }
        etapa(ctx, "instalando")
        return try {
            val instalador = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = instalador.createSession(params)
            instalador.openSession(id).use { sessao ->
                sessao.openWrite("financas.apk", 0, arquivo.length()).use { saida ->
                    arquivo.inputStream().use { it.copyTo(saida) }
                    sessao.fsync(saida)
                }
                val pi = PendingIntent.getBroadcast(ctx, 60, Intent(ctx, ReceptorInstalacao::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                sessao.commit(pi.intentSender)
            }
            null
        } catch (e: Exception) {
            etapa(ctx, "", "Falha ao instalar: " + (e.message ?: "erro"))
            "Falha ao instalar"
        }
    }

    /** Notificação "nova versão": tocar abre o app, que instala. */
    fun avisar(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Atualizações do app", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 61, Intent(ctx, MainActivity::class.java).putExtra("atualizar", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val versao = p(ctx).getString("versaoNova", "") ?: ""
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("Nova versão do Finanças")
            .setContentText("Toque para atualizar para a $versao")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(5, n)
    }

    fun resultado(ctx: Context, status: Int, msg: String?, confirmar: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // primeira atualização (ou Android antigo): o sistema pede confirmação
                etapa(ctx, "confirmar")
                confirmar?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { try { ctx.startActivity(it) } catch (e: Exception) { avisar(ctx) } }
            }
            PackageInstaller.STATUS_SUCCESS -> etapa(ctx, "")
            else -> etapa(ctx, "", "A instalação não terminou" + (msg?.let { ": $it" } ?: ""))
        }
    }
}

class ReceptorInstalacao : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirmar = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        Atualizador.resultado(ctx, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirmar)
    }
}
