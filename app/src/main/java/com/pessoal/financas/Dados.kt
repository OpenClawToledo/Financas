package com.pessoal.financas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale

/** Resultado do interpretador de texto: despesa (receita = false) ou entrada de dinheiro. */
data class Gasto(
    val quando: Long,
    val valor: Double,
    val descricao: String,
    val categoria: String,
    val receita: Boolean = false,
    val categoriaEscrita: Boolean = false   // o usuário escreveu a categoria no texto
)

data class Lanc(val id: String, val q: Long, val v: Double, val d: String, val c: String, val receita: Boolean, val sh: Boolean, val dono: String, val cf: String)
/** Uma ocorrência da conta: a data em que vence, qual é (1 = primeira) e a chave usada para marcar como paga. */
data class Ocorrencia(val data: java.time.LocalDate, val n: Int, val chave: String)

/**
 * Conta que se repete ou está programada.
 * freq: "m" mensal (ini = yyyy-MM, dia do mês), "q" a cada 15 dias ou "s" semanal (d0 = primeira data, yyyy-MM-dd).
 * vezes: 0 = sem fim, 1 = data única, N = N vezes (parcelas). variavel = o valor muda (o guardado é a estimativa).
 */
data class ContaFixa(
    val id: String, val nome: String, val valor: Double, val dia: Int, val entrada: Boolean, val sh: Boolean, val dono: String,
    val ini: String = "", val vezes: Int = 0, val variavel: Boolean = false, val categoria: String = "",
    val freq: String = "m", val d0: String = "",
    val pm: Int = 1   // mensais: a cada quantos meses (1, 2, 3, 6, 12)
) {
    private fun idx(m: String): Int? = m.split("-").takeIf { it.size == 2 }?.let { (a, b) -> a.toIntOrNull()?.let { y -> b.toIntOrNull()?.let { y * 12 + it - 1 } } }

    /** Parcela mensal deste mês (1 = primeira) ou null. Só para contas mensais. */
    fun parcelaEm(mes: String): Int? {
        val i0 = idx(ini) ?: return if (vezes == 0) 1 else null
        val i = (idx(mes) ?: return null) - i0
        val p = maxOf(1, pm)
        if (i < 0 || i % p != 0) return null
        val n = i / p
        return if (vezes == 0 || n < vezes) n + 1 else null
    }

    fun passoDias(): Int = if (freq == "q") 14 else 7

    fun inicio(): java.time.LocalDate? = try { java.time.LocalDate.parse(d0) } catch (e: Exception) {
        idx(ini)?.let { java.time.LocalDate.of(it / 12, it % 12 + 1, 1).let { m -> m.withDayOfMonth(minOf(dia, m.lengthOfMonth())) } }
    }

    /** Todas as ocorrências dentro do mês (ano, mês 1-12). */
    fun ocorrenciasNoMes(ano: Int, mes: Int): List<Ocorrencia> {
        val primeiro = java.time.LocalDate.of(ano, mes, 1)
        val ultimo = primeiro.withDayOfMonth(primeiro.lengthOfMonth())
        if (freq != "q" && freq != "s") {
            val chave = String.format("%04d-%02d", ano, mes)
            val n = parcelaEm(chave) ?: return emptyList()
            return listOf(Ocorrencia(primeiro.withDayOfMonth(minOf(dia, primeiro.lengthOfMonth())), n, chave))
        }
        val ini = inicio() ?: return emptyList()
        val passo = passoDias().toLong()
        var k = maxOf(0L, Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(ini, primeiro) + passo - 1, passo))
        val lista = ArrayList<Ocorrencia>()
        while (true) {
            if (vezes > 0 && k >= vezes) break
            val d = ini.plusDays(k * passo)
            if (d.isAfter(ultimo)) break
            lista.add(Ocorrencia(d, (k + 1).toInt(), d.toString()))
            k++
        }
        return lista
    }

    fun ocorrenciasAgora(): List<Ocorrencia> = java.time.LocalDate.now().let { ocorrenciasNoMes(it.year, it.monthValue) }
    fun ativaEm(mes: String) = mes.split("-").let { ocorrenciasNoMes(it[0].toInt(), it[1].toInt()).isNotEmpty() }
}
data class Meta(val id: String, val nome: String, val alvo: Double, val emoji: String, val tipo: String, val sh: Boolean, val dono: String, val atual: Double)

/** Preferências deste aparelho (não sincronizam). */
object Ajustes {
    private fun p(ctx: Context) = ctx.getSharedPreferences("financas", Context.MODE_PRIVATE)

    fun nome(ctx: Context): String = p(ctx).getString("nome", "Eu") ?: "Eu"
    fun salvarNome(ctx: Context, nome: String) { p(ctx).edit().putString("nome", nome.ifBlank { "Eu" }).commit() }

    fun moeda(ctx: Context): String = p(ctx).getString("moeda", "EUR") ?: "EUR"
    fun salvarMoeda(ctx: Context, codigo: String) {
        val c = if (codigo == "BRL") "BRL" else "EUR"
        p(ctx).edit().putString("moeda", c).commit()
        Formato.codigo = c
    }

    fun notifAtiva(ctx: Context): Boolean = p(ctx).getBoolean("notif", false)
    fun definirNotifAtiva(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("notif", v).commit() }
    fun agitarAtivo(ctx: Context): Boolean = p(ctx).getBoolean("agitar", false)
    fun definirAgitar(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("agitar", v).commit() }

    /** 0 = pouco sensível (agitar forte), 1 = normal, 2 = muito sensível. */
    fun sensibilidade(ctx: Context): Int = p(ctx).getInt("sens", 1)
    fun definirSensibilidade(ctx: Context, v: Int) { p(ctx).edit().putInt("sens", v.coerceIn(0, 2)).commit() }

    fun conquistasVistas(ctx: Context): JSONArray = try { JSONArray(p(ctx).getString("conquistas", "[]")) } catch (e: Exception) { JSONArray() }
    fun marcarConquista(ctx: Context, id: String) {
        val arr = conquistasVistas(ctx)
        for (i in 0 until arr.length()) if (arr.getString(i) == id) return
        arr.put(id)
        p(ctx).edit().putString("conquistas", arr.toString()).commit()
    }
}

/** Operações do app sobre os registros. Toda mudança agenda uma sincronização. */
object Armazem {
    private fun b(ctx: Context) = Banco.de(ctx)
    fun eu(ctx: Context): String = Sessao.eu(ctx)

    private fun novo(ctx: Context, tipo: String, sh: Boolean, dados: JSONObject): String {
        val id = Banco.novoId()
        b(ctx).salvar(Reg(id, tipo, eu(ctx), sh, dados))
        Nuvem.agendar(ctx)
        return id
    }

    private fun alterar(ctx: Context, id: String, bloco: (Reg) -> Reg?) {
        val r = b(ctx).um(id) ?: return
        if (r.dono != eu(ctx)) return   // só o dono altera o que é seu
        bloco(r)?.let { b(ctx).salvar(it); Nuvem.agendar(ctx) }
    }

    fun apagar(ctx: Context, id: String) = alterar(ctx, id) { it.copy(apagado = true) }
    fun restaurar(ctx: Context, id: String) = alterar(ctx, id) { it.copy(apagado = false) }

    // ---------- Lançamentos ----------
    private fun paraLanc(r: Reg): Lanc {
        val o = r.dados
        val rec = o.optString("t") == "r"
        val d = o.optString("d")
        return Lanc(r.id, o.optLong("q"), o.optDouble("v", 0.0), d, Categorias.canon(o.optString("c")).ifEmpty { Categorias.detectar(d, rec) }, rec, r.sh, r.dono, o.optString("cf"))
    }

    fun lancamentos(ctx: Context): List<Lanc> = b(ctx).todos("lanc").map { paraLanc(it) }.sortedByDescending { it.q }

    private fun dadosLanc(g: Gasto) = JSONObject().put("q", g.quando).put("v", g.valor).put("d", g.descricao)
        .put("c", g.categoria).put("t", if (g.receita) "r" else "d")

    fun adicionar(ctx: Context, g: Gasto, sh: Boolean = false): String = novo(ctx, "lanc", sh, dadosLanc(g))

    fun editar(ctx: Context, id: String, g: Gasto, sh: Boolean) {
        val r = b(ctx).um(id) ?: return
        if (r.dono != eu(ctx)) return
        val dados = dadosLanc(g)
        r.dados.optString("cf").takeIf { it.isNotEmpty() }?.let { dados.put("cf", it) }
        if (r.sh && !sh) {
            // Deixar de compartilhar: o registro antigo é apagado (o parceiro recebe a remoção) e nasce um privado
            b(ctx).salvar(r.copy(apagado = true))
            b(ctx).salvar(Reg(Banco.novoId(), "lanc", r.dono, false, dados))
        } else b(ctx).salvar(r.copy(sh = sh, dados = dados))
        Nuvem.agendar(ctx)
    }

    fun duplicar(ctx: Context, id: String) {
        val l = b(ctx).um(id)?.let { paraLanc(it) } ?: return
        adicionar(ctx, Gasto(System.currentTimeMillis(), l.v, l.d, l.c, l.receita), l.sh && l.dono == eu(ctx))
    }

    fun mudarCategoria(ctx: Context, id: String, c: String) = alterar(ctx, id) { it.copy(dados = JSONObject(it.dados.toString()).put("c", c)) }

    private fun inicio(): Long = Datas.inicioDoMes()
    fun gastosDoMes(ctx: Context): List<Lanc> = lancamentos(ctx).filter { it.q >= inicio() && !it.receita }
    fun receitasDoMes(ctx: Context): Double = lancamentos(ctx).filter { it.q >= inicio() && it.receita }.sumOf { it.v }
    fun totalDoMes(ctx: Context): Double = gastosDoMes(ctx).sumOf { it.v }

    // ---------- Curtidas e comentários (cada um é dono dos seus) ----------
    fun curtidas(ctx: Context): List<Reg> = b(ctx).todos("curtida")
    fun comentarios(ctx: Context): List<Reg> = b(ctx).todos("coment")

    fun curtir(ctx: Context, alvo: String) {
        val me = eu(ctx)
        val minha = b(ctx).todos("curtida").firstOrNull { it.dono == me && it.dados.optString("alvo") == alvo }
        if (minha != null) apagar(ctx, minha.id)
        else novo(ctx, "curtida", compartilhadoDe(ctx, alvo), JSONObject().put("alvo", alvo))
    }

    fun comentar(ctx: Context, alvo: String, texto: String) {
        novo(ctx, "coment", compartilhadoDe(ctx, alvo), JSONObject().put("alvo", alvo).put("t", texto)
            .put("a", Ajustes.nome(ctx)).put("q", System.currentTimeMillis()))
    }

    /** Interações num item compartilhado também são visíveis ao casal. */
    private fun compartilhadoDe(ctx: Context, alvo: String) = b(ctx).um(alvo)?.sh ?: false

    // ---------- Contas fixas (a pagar e entradas) ----------
    fun contas(ctx: Context): List<ContaFixa> = b(ctx).todos("conta").map {
        val o = it.dados
        ContaFixa(it.id, o.optString("n"), o.optDouble("v"), o.optInt("dia", 1), o.optBoolean("e"), it.sh, it.dono,
            o.optString("ini"), o.optInt("vezes", 0), o.optBoolean("var"), o.optString("c"),
            o.optString("freq", "m").ifEmpty { "m" }, o.optString("d0"), maxOf(1, o.optInt("pm", 1)))
    }.sortedBy { it.dia }

    /** Contas que acontecem no mês atual. */
    fun contasDoMes(ctx: Context): List<ContaFixa> = contas(ctx).filter { it.ativaEm(Datas.mesAtual()) }

    fun adicionarConta(ctx: Context, nome: String, valor: Double, dia: Int, entrada: Boolean, sh: Boolean,
                       ini: String = "", vezes: Int = 0, variavel: Boolean = false, categoria: String = "",
                       freq: String = "m", d0: String = "", pm: Int = 1): String =
        novo(ctx, "conta", sh, JSONObject().put("n", nome).put("v", valor).put("dia", dia).put("e", entrada)
            .put("ini", ini).put("vezes", vezes).put("var", variavel).put("c", categoria).put("freq", freq).put("d0", d0).put("pm", maxOf(1, pm)))

    /**
     * "Este e os próximos": muda a conta daqui para frente (nome, valor, categoria e, se for mensal, o dia).
     * Os lançamentos já feitos e os pagamentos registados ficam como estão.
     */
    fun editarConta(ctx: Context, id: String, nome: String, valor: Double, categoria: String, dia: Int) = alterar(ctx, id) { r ->
        val o = JSONObject(r.dados.toString()).put("n", nome).put("v", valor).put("c", categoria)
        if (dia in 1..31 && o.optString("freq", "m").ifEmpty { "m" } == "m") o.put("dia", dia)
        r.copy(dados = o)
    }

    /** Troca o valor de referência da conta (contas de valor variável: a última fatura vira a estimativa). */
    fun mudarValorConta(ctx: Context, id: String, valor: Double) = alterar(ctx, id) { it.copy(dados = JSONObject(it.dados.toString()).put("v", valor)) }

    /**
     * Regista que uma ocorrência foi paga, em qualquer mês (também no passado): o registo "pago" e o lançamento
     * na data em que aconteceu. Não faz nada se essa ocorrência já estiver paga.
     */
    fun pagarOcorrencia(ctx: Context, c: ContaFixa, chave: String, n: Int, quando: Long, valor: Double) {
        if (pagoDe(ctx, c.id, chave) != null) return
        novo(ctx, "pago", c.sh, JSONObject().put("conta", c.id).put("mes", chave))
        val nome = if (c.vezes > 1) "${c.nome} ($n/${c.vezes})" else c.nome
        val d = JSONObject().put("q", quando).put("v", valor).put("d", nome).put("cf", c.id).put("oc", chave)
        if (c.entrada) d.put("t", "r").put("c", Categorias.canon(c.categoria).ifEmpty { Categorias.detectar(c.nome, true) })
        else d.put("t", "d").put("c", Categorias.canon(c.categoria).ifEmpty { Categorias.detectar(c.nome) })
        novo(ctx, "lanc", c.sh, d)
    }

    /** Registro de pagamento de uma ocorrência (chave = yyyy-MM nas mensais, yyyy-MM-dd nas semanais/quinzenais). */
    fun pagoDe(ctx: Context, contaId: String, chave: String): Reg? =
        b(ctx).todos("pago").firstOrNull { it.dados.optString("conta") == contaId && it.dados.optString("mes") == chave }

    /** Ocorrências deste mês com quem as pagou (null = em aberto). */
    fun ocorrenciasComPagamento(ctx: Context, c: ContaFixa): List<Pair<Ocorrencia, Reg?>> =
        c.ocorrenciasAgora().map { it to pagoDe(ctx, c.id, it.chave) }

    /** Paga = todas as ocorrências deste mês estão pagas. */
    fun estaPaga(ctx: Context, contaId: String): Boolean {
        val c = contas(ctx).firstOrNull { it.id == contaId } ?: return false
        val oc = ocorrenciasComPagamento(ctx, c)
        return oc.isNotEmpty() && oc.all { it.second != null }
    }

    fun quemPagou(ctx: Context, contaId: String): String? {
        val c = contas(ctx).firstOrNull { it.id == contaId } ?: return null
        return ocorrenciasComPagamento(ctx, c).firstNotNullOfOrNull { it.second }?.dono
    }

    /**
     * Marca (ou desmarca) uma ocorrência como paga/recebida e cria o lançamento correspondente.
     * chave vazia = a primeira em aberto deste mês (ou a última paga, para desmarcar).
     */
    fun alternarPaga(ctx: Context, contaId: String, valorReal: Double? = null, chave: String = ""): Boolean {
        val c = contas(ctx).firstOrNull { it.id == contaId } ?: return false
        val ocs = ocorrenciasComPagamento(ctx, c)
        if (ocs.isEmpty()) return false
        val alvo = if (chave.isNotEmpty()) ocs.firstOrNull { it.first.chave == chave } ?: return false
                   else ocs.firstOrNull { it.second == null } ?: ocs.last()
        val (oc, pago) = alvo
        if (pago != null) {
            if (pago.dono != eu(ctx)) return false   // quem marcou é quem pode desmarcar
            apagar(ctx, pago.id)
            val inicio = inicio()
            b(ctx).todos("lanc").firstOrNull {
                it.dono == eu(ctx) && it.dados.optString("cf") == contaId &&
                    (it.dados.optString("oc") == oc.chave || (it.dados.optString("oc").isEmpty() && c.freq == "m" && it.dados.optLong("q") >= inicio))
            }?.let { apagar(ctx, it.id) }
            return true
        }
        novo(ctx, "pago", c.sh, JSONObject().put("conta", contaId).put("mes", oc.chave))
        val nome = if (c.vezes > 1) "${c.nome} (${oc.n}/${c.vezes})" else c.nome
        val d = JSONObject().put("q", System.currentTimeMillis()).put("v", valorReal ?: c.valor).put("d", nome).put("cf", contaId).put("oc", oc.chave)
        if (c.entrada) d.put("t", "r").put("c", Categorias.canon(c.categoria).ifEmpty { Categorias.detectar(c.nome, true) })
        else d.put("t", "d").put("c", Categorias.canon(c.categoria).ifEmpty { Categorias.detectar(c.nome) })
        novo(ctx, "lanc", c.sh, d)
        return true
    }

    fun registrarFeedback(ctx: Context, dados: JSONObject) { novo(ctx, "feedback", false, dados) }

    // ---------- Categorias criadas pela pessoa: {n, pai, e, cor} ----------
    fun categoriasProprias(ctx: Context): List<Reg> = b(ctx).todos("categoria").filter { it.dono == eu(ctx) }
    fun caminhoCategoria(r: Reg): String {
        val pai = r.dados.optString("pai"); val n = r.dados.optString("n")
        return if (pai.isEmpty()) n else pai + Categorias.SEP + n
    }
    fun salvarCategoria(ctx: Context, nome: String, pai: String, emoji: String, cor: String): String =
        novo(ctx, "categoria", false, JSONObject().put("n", nome).put("pai", pai).put("e", emoji).put("cor", cor))

    // ---------- Plano (Premium) ----------
    /**
     * Premium ativo? No servidor com o Pro (pro_admin.sql), vale a validade dada pelo admin (o admin tem sempre).
     * Sem essas funções no servidor, vale o registro "plano" da própria pessoa, ativado no app.
     */
    fun premium(ctx: Context): Boolean =
        if (Sessao.proServidor(ctx)) Sessao.proAdmin(ctx) || Sessao.proExpira(ctx) > System.currentTimeMillis()
        else b(ctx).todos("plano").filter { it.dono == eu(ctx) }.any { it.dados.optBoolean("premium") }

    /** Liga ou desliga o Premium. Desligar não apaga nada: só esconde os recursos Premium. */
    fun definirPremium(ctx: Context, ativo: Boolean) {
        val meus = b(ctx).todos("plano").filter { it.dono == eu(ctx) }
        val d = JSONObject().put("premium", ativo).put("q", System.currentTimeMillis())
        if (meus.isEmpty()) novo(ctx, "plano", false, d)
        else { meus.forEach { b(ctx).salvar(it.copy(dados = d)) }; Nuvem.agendar(ctx) }
    }

    // ---------- Destinos: juntar (meta) ou amortizar (imprevisto) ----------
    fun metas(ctx: Context): List<Meta> {
        val aportes = b(ctx).todos("aporte").groupBy { it.dados.optString("meta") }
        return b(ctx).todos("meta").map { r ->
            val o = r.dados
            Meta(r.id, o.optString("n"), o.optDouble("alvo"), o.optString("e", "🎯"), o.optString("tipo", "j"), r.sh, r.dono,
                maxOf(0.0, aportes[r.id]?.sumOf { it.dados.optDouble("v", 0.0) } ?: 0.0))
        }
    }

    fun adicionarMeta(ctx: Context, nome: String, alvo: Double, emoji: String, tipo: String, sh: Boolean) {
        novo(ctx, "meta", sh, JSONObject().put("n", nome).put("alvo", alvo).put("e", emoji).put("tipo", if (tipo == "a") "a" else "j"))
    }

    /** Cada aporte é um registro de quem guardou: assim os dois podem contribuir para um destino do casal. */
    fun aportar(ctx: Context, metaId: String, valor: Double, trocaId: String = ""): Boolean {
        val m = b(ctx).um(metaId)?.takeIf { !it.apagado } ?: return false
        val d = JSONObject().put("meta", metaId).put("v", valor).put("q", System.currentTimeMillis())
        if (trocaId.isNotEmpty()) d.put("troca", trocaId)
        novo(ctx, "aporte", m.sh, d)
        return true
    }

    fun removerMeta(ctx: Context, id: String) = apagar(ctx, id)

    // ---------- Referências (pessoais) e trocas ----------
    fun referencias(ctx: Context): List<Reg> {
        val me = eu(ctx)
        val minhas = b(ctx).todos("ref").filter { it.dono == me }
        if (minhas.isEmpty() && !b(ctx).existeTipoDoDono("ref", me)) {
            // primeira vez: começa com o café da manhã como referência
            novo(ctx, "ref", false, JSONObject().put("n", "Café da manhã").put("e", "☕").put("v", 3.5).put("dest", true).put("evitar", false).put("m", ""))
            return b(ctx).todos("ref").filter { it.dono == me }
        }
        return minhas
    }

    fun salvarReferencia(ctx: Context, id: String, nome: String, emoji: String, valor: Double, destaque: Boolean, evitar: Boolean, meta: String) {
        val d = JSONObject().put("n", nome).put("e", emoji).put("v", valor).put("dest", destaque).put("evitar", evitar).put("m", meta)
        val r = if (id.isNotEmpty()) b(ctx).um(id) else null
        if (r != null && r.dono == eu(ctx)) { b(ctx).salvar(r.copy(dados = d)); Nuvem.agendar(ctx) }
        else novo(ctx, "ref", false, d)
    }

    fun paraEvitar(ctx: Context): List<Reg> = referencias(ctx).filter { it.dados.optBoolean("evitar") }

    fun trocas(ctx: Context): List<Reg> = b(ctx).todos("troca").filter { it.dono == eu(ctx) }

    /** "Evitei": a troca fica privada; o valor entra no destino como aporte (visível se o destino for do casal). */
    fun registrarTroca(ctx: Context, refId: String): String? {
        val r = b(ctx).um(refId)?.takeIf { !it.apagado } ?: return null
        val o = r.dados
        val v = o.optDouble("v", 0.0)
        val metaId = o.optString("m")
        val meta = metas(ctx).firstOrNull { it.id == metaId }
        val trocaId = novo(ctx, "troca", false, JSONObject().put("q", System.currentTimeMillis()).put("r", refId).put("v", v)
            .put("n", o.optString("n")).put("e", o.optString("e")).put("m", meta?.id ?: ""))
        if (meta != null) aportar(ctx, meta.id, v, trocaId)
        val destino = meta?.let { (if (it.tipo == "a") " abatido em " else " para ") + it.nome } ?: " poupado"
        return "Evitou ${o.optString("e")} ${o.optString("n")}: ${Formato.moeda(v)}$destino"
    }

    /** Apagar ou restaurar uma troca leva junto o aporte que ela gerou. */
    fun apagarTroca(ctx: Context, id: String, restaurarEla: Boolean) {
        if (restaurarEla) restaurar(ctx, id) else apagar(ctx, id)
        val me = eu(ctx)
        Banco.de(ctx).todosComApagados("aporte")
            .filter { it.dono == me && it.dados.optString("troca") == id }
            .forEach { if (restaurarEla) restaurar(ctx, it.id) else apagar(ctx, it.id) }
    }
}

object Datas {
    fun inicioDoMes(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun mesAtual(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    fun diaHoje(): Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    fun diasNoMes(): Int = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)

    /** Dia de vencimento efetivo (conta no dia 31 vence no último dia em meses curtos). */
    fun diaEfetivo(dia: Int): Int = minOf(dia, diasNoMes())
}

/** Números do mês para a notificação (considera tudo o que esta pessoa vê). */
object Previsao {
    data class Resultado(val jaGasto: Double, val receitas: Double, val livre: Double, val emCaixa: Double, val saiuHoje: Double)

    fun doMes(ctx: Context): Resultado {
        val agora = System.currentTimeMillis()
        val todos = Armazem.lancamentos(ctx).filter { it.q <= agora }
        val iniMes = Datas.inicioDoMes()
        val iniDia = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val jaGasto = todos.filter { !it.receita && it.q >= iniMes }.sumOf { it.v }
        val receitas = todos.filter { it.receita && it.q >= iniMes }.sumOf { it.v }
        // em caixa = tudo o que entrou menos tudo o que saiu, desde o primeiro lançamento
        val emCaixa = todos.sumOf { if (it.receita) it.v else -it.v }
        val saiuHoje = todos.filter { !it.receita && it.q >= iniDia }.sumOf { it.v }
        return Resultado(jaGasto, receitas, receitas - jaGasto, emCaixa, saiuHoje)
    }

    /** Contas a pagar ainda em aberto neste mês, com dias até o vencimento (negativo = atrasada). */
    fun proximasContas(ctx: Context): List<Pair<ContaFixa, Int>> {
        val hoje = Datas.diaHoje()
        return Armazem.contasDoMes(ctx).filter { !it.entrada }.mapNotNull { c ->
            Armazem.ocorrenciasComPagamento(ctx, c).firstOrNull { it.second == null }?.let { c to (it.first.data.dayOfMonth - hoje) }
        }.sortedBy { it.second }
    }
}

/**
 * Ajusta frases ditadas por voz (ou pelo teclado com ditado) antes de interpretar:
 * "5 euros e 50 de café" → "5,50 café"; "12 vírgula 5" → "12,5"; "paguei 20 no lidl" → "20 lidl"; "recebi 100" → "+100".
 */
object Fala {
    private val centimos = Regex("""(?i)(\d+)\s*(?:€|euros?|reais|r\$)\s+e\s+(\d{1,2})(?!\d)(?:\s*(?:cêntimos?|centimos?|centavos?))?""")
    private val virgula = Regex("""(?i)(\d+)\s+v[íi]rgula\s+(\d{1,2})(?!\d)""")
    private val gastei = Regex("""(?i)^(?:eu\s+)?(?:gastei|paguei|comprei)\s+""")
    private val recebi = Regex("""(?i)^(?:eu\s+)?(?:recebi|ganhei)\s+""")

    fun normalizar(texto: String): String {
        var s = texto.trim()
        s = centimos.replace(s) { it.groupValues[1] + "," + it.groupValues[2].padStart(2, '0') }
        s = virgula.replace(s) { it.groupValues[1] + "," + it.groupValues[2] }
        if (recebi.containsMatchIn(s)) s = "+" + recebi.replace(s, "")
        s = gastei.replace(s, "")
        return s
    }

    private val ligacaoInicio = Regex("""(?i)^(?:de|do|da|dos|das|no|na|nos|nas|em|com|pra|para)\s+""")
    private val ligacaoFim = Regex("""(?i)\s+(?:de|do|da|por|no|na|em|com)$""")

    /** Tira as palavras de ligação que sobram na descrição ("de café" → "café"). */
    fun limparDescricao(d: String): String = ligacaoFim.replace(ligacaoInicio.replace(d.trim(), ""), "").trim()
}

/** Entende "5,50 café", "mercado 45,90", "1.250,00 renda" e "+1090 salário" (o + indica entrada). */
object Interpretador {
    private val numero = Regex("""\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?(?!\d)|\d+(?:[.,]\d{1,2})?(?!\d)""")
    private val milhar = Regex("""\d{1,3}(?:\.\d{3})+""")
    private val simbolos = Regex("""(?i)r\$|€|\beuros?\b|\beur\b|\breais\b""")
    private val moedaDepois = Regex("""^\s*(?:€|(?i:euros?|eur|reais)\b)""")

    fun paraNumero(s: String): Double? {
        var x = s.trim().replace(simbolos, "").trim()
        if (x.contains(',')) x = x.replace(".", "").replace(',', '.')
        else if (milhar.matches(x)) x = x.replace(".", "")
        return x.toDoubleOrNull()
    }

    fun interpretar(texto: String, forcarReceita: Boolean = false): Gasto? {
        var t = Fala.normalizar(texto)
        var receita = forcarReceita
        if (t.startsWith("+")) { receita = true; t = t.substring(1).trim() }
        val todos = numero.findAll(t).toList()
        if (todos.isEmpty()) return null
        // "2 cafés 3 euros": vale o número colado à moeda
        val m = todos.firstOrNull { moedaDepois.containsMatchIn(t.substring(it.range.last + 1)) }
            ?: todos.firstOrNull { t.substring(0, it.range.first).trimEnd().endsWith("R$", true) }
            ?: todos.first()
        val valor = paraNumero(m.value) ?: return null
        if (valor <= 0) return null
        val desc = Fala.limparDescricao(t.removeRange(m.range).replace(simbolos, "").replace(Regex("\\s+"), " "))
            .ifEmpty { if (receita) "Entrada" else "Sem descrição" }
        // categoria escrita no fim; se a descrição já leva a ela ("conta da água"), a descrição fica inteira
        Categorias.escrita(desc, receita)?.let { (cat, resto) ->
            if (desc.contains('#') || Categorias.detectar(desc, receita) != cat) return Gasto(System.currentTimeMillis(), valor, resto, cat, receita, true)
        }
        return Gasto(System.currentTimeMillis(), valor, desc, Categorias.detectar(desc, receita), receita)
    }
}

object Formato {
    /** Moeda atual; carregada ao iniciar o app (AppFinancas) e ao mudar nos ajustes. */
    @Volatile var codigo: String = "EUR"

    private fun local(): Locale = if (codigo == "BRL") Locale("pt", "BR") else Locale("pt", "PT")

    fun moeda(v: Double): String = NumberFormat.getCurrencyInstance(local()).apply {
        currency = Currency.getInstance(codigo)
    }.format(v)

    fun prazo(dias: Int): String = when {
        dias < 0 -> "atrasada ${-dias} dia(s)"
        dias == 0 -> "vence hoje"
        dias == 1 -> "vence amanhã"
        else -> "vence em $dias dias"
    }

    /** Traduz dinheiro nas referências que a pessoa escolheu destacar. */
    fun equivalencia(ctx: Context, valor: Double): String =
        Armazem.referencias(ctx).map { it.dados }
            .filter { it.optBoolean("dest") && it.optDouble("v", 0.0) > 0 }
            .take(2)
            .joinToString(" · ") { String.format(local(), "%.1f× %s %s", valor / it.optDouble("v"), it.optString("e"), it.optString("n")) }
}
