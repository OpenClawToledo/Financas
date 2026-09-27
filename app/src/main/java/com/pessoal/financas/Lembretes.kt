package com.pessoal.financas

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Verificação diária (por volta das 9h) de contas fixas que vencem nos próximos 3 dias. */
object Lembretes {
    private const val CANAL = "lembretes_contas"
    private const val ID = 2

    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 20, Intent(ctx, ReceptorLembrete::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val inicio = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, inicio, AlarmManager.INTERVAL_DAY, pi)
    }

    fun verificar(ctx: Context) {
        val proximas = Previsao.proximasContas(ctx).filter { it.second <= 3 }
        if (proximas.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Lembretes de contas", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val texto = proximas.joinToString("\n") { (c, d) ->
            "${c.nome}: ${Formato.moeda(c.valor)} — ${Formato.prazo(d)}"
        }
        val total = proximas.sumOf { it.first.valor }
        val piAbrir = PendingIntent.getActivity(
            ctx, 21, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("Contas próximas: " + Formato.moeda(total))
            .setContentText(texto.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(piAbrir)
            .setAutoCancel(true)
            .build()
        nm.notify(ID, n)
    }
}

class ReceptorLembrete : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Lembretes.verificar(ctx)
        // recoloca a notificação fixa, caso o sistema a tenha removido
        if (Ajustes.notifAtiva(ctx) || Ajustes.agitarAtivo(ctx)) Notificacao.mostrar(ctx)
        Nuvem.agendar(ctx, 0)
    }
}

/** Reagenda o lembrete depois que o celular reinicia. */
class ReceptorBoot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // ao ligar o celular e logo depois de o app ser atualizado
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Lembretes.agendar(ctx)
            if (Ajustes.agitarAtivo(ctx)) Agitar.sincronizar(ctx)
            else if (Ajustes.notifAtiva(ctx)) Notificacao.mostrar(ctx)
        }
    }
}
