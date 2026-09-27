package com.pessoal.financas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.widget.Toast

object Notificacao {
    const val CANAL_FIXA = "lancamento_rapido"
    const val CANAL_POPUP = "popup_rapido"
    const val ID = 1
    const val ID_POPUP = 3
    const val CHAVE_TEXTO = "texto_gasto"
    const val ACAO_ADICIONAR = "com.pessoal.financas.ADICIONAR"
    const val ACAO_EVITEI = "com.pessoal.financas.EVITEI"
    const val EXTRA_POPUP = "popup"
    const val EXTRA_REF = "ref"

    fun criarCanais(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_FIXA, "Lançamento rápido (fixa)", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        // Importância alta = aparece flutuando por cima dos apps, como uma mensagem do WhatsApp
        nm.createNotificationChannel(
            NotificationChannel(CANAL_POPUP, "Popup ao agitar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Aparece por cima de qualquer app quando você agita o celular"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    /** Ação com campo de texto. Um "+" no começo registra uma entrada de dinheiro. */
    private fun acaoTexto(ctx: Context, popup: Boolean): Notification.Action {
        val entrada = RemoteInput.Builder(CHAVE_TEXTO).setLabel("5,50 café  ·  +1090 salário").build()
        val intent = Intent(ctx, ReceptorGasto::class.java).setAction(ACAO_ADICIONAR).putExtra(EXTRA_POPUP, popup)
        val pi = PendingIntent.getBroadcast(
            ctx, if (popup) 11 else 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_add), "Lançar", pi)
            .addRemoteInput(entrada).setAllowGeneratedReplies(false).build()
    }

    /** Botões "Evitei …" para as referências marcadas como algo a evitar. */
    private fun acoesEvitei(ctx: Context, popup: Boolean, maximo: Int): List<Notification.Action> =
        Armazem.paraEvitar(ctx).take(maximo).mapIndexed { i, r ->
            val intent = Intent(ctx, ReceptorGasto::class.java).setAction(ACAO_EVITEI)
                .putExtra(EXTRA_REF, r.id).putExtra(EXTRA_POPUP, popup)
            val pi = PendingIntent.getBroadcast(
                ctx, (if (popup) 200 else 100) + i, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_add),
                "Evitei ${r.dados.optString("e")} ${r.dados.optString("n")}", pi
            ).build()
        }

    private fun abrirApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 2, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Notificação fixa (também usada pelo serviço de agitar). */
    fun construir(ctx: Context, mensagem: String? = null): Notification {
        criarCanais(ctx)
        val p = Previsao.doMes(ctx)
        val titulo = if (p.receitas > 0) "Livre: ${Formato.moeda(p.livre)} · Saiu ${Formato.moeda(p.jaGasto)}"
        else "Este mês: ${Formato.moeda(p.jaGasto)}"

        val equiv = Formato.equivalencia(ctx, p.jaGasto)
        val proxima = Previsao.proximasContas(ctx).firstOrNull()
        val linhaConta = proxima?.let { (c, d) -> "Próxima: ${c.nome} ${Formato.moeda(c.valor)}, ${Formato.prazo(d)}" }
        val padrao = listOfNotNull(
            if (equiv.isNotEmpty()) "= $equiv" else null,
            linhaConta,
            if (Ajustes.agitarAtivo(ctx)) "Agite o celular para lançar de qualquer lugar" else null
        ).joinToString("\n").ifEmpty { "Toque em Lançar" }
        val texto = mensagem ?: padrao

        val b = Notification.Builder(ctx, CANAL_FIXA)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrirApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(acaoTexto(ctx, false))
        acoesEvitei(ctx, false, 2).forEach { b.addAction(it) }
        return b.build()
    }

    fun mostrar(ctx: Context, mensagem: String? = null) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID, construir(ctx, mensagem))
    }

    /** Popup flutuante com campo de resposta, disparado ao agitar. */
    fun popup(ctx: Context) {
        criarCanais(ctx)
        val evitar = Armazem.paraEvitar(ctx)
        val dica = if (evitar.isEmpty()) "Escreva o valor e o que foi. Use + para entradas."
        else "Escreva o valor e o que foi, ou toque em Evitei."
        val b = Notification.Builder(ctx, CANAL_POPUP)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("Novo lançamento")
            .setContentText(dica)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(abrirApp(ctx))
            .setAutoCancel(true)
            .setTimeoutAfter(45_000)
            .addAction(acaoTexto(ctx, true))
        acoesEvitei(ctx, true, 2).forEach { b.addAction(it) }
        ctx.getSystemService(NotificationManager::class.java).notify(ID_POPUP, b.build())
    }

    fun fecharPopup(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_POPUP)
}

class ReceptorGasto : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val popup = intent.getBooleanExtra(Notificacao.EXTRA_POPUP, false)
        val msg: String = when (intent.action) {
            Notificacao.ACAO_EVITEI ->
                Armazem.registrarTroca(ctx, intent.getStringExtra(Notificacao.EXTRA_REF) ?: "") ?: "Referência não encontrada"
            else -> {
                val texto = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notificacao.CHAVE_TEXTO)?.toString()
                val g = texto?.let { Interpretador.interpretar(it) }
                if (g != null) {
                    Armazem.adicionar(ctx, g)
                    val sinal = if (g.receita) "+" else ""
                    val equiv = if (g.receita) "" else Formato.equivalencia(ctx, g.valor)
                    "✓ $sinal${Formato.moeda(g.valor)} ${g.descricao} (${g.categoria})" + if (equiv.isNotEmpty()) "\n= $equiv" else ""
                } else "Não entendi. Use algo como: 5,50 café"
            }
        }
        if (popup) Notificacao.fecharPopup(ctx)
        // A notificação fixa sempre é republicada; senão a resposta fica "carregando"
        if (Ajustes.notifAtiva(ctx) || Ajustes.agitarAtivo(ctx) || !popup) Notificacao.mostrar(ctx, msg)
        if (popup) Toast.makeText(ctx, msg.lineSequence().first(), Toast.LENGTH_SHORT).show()
    }
}
