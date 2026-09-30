package com.pessoal.financas

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/** Monta tudo o que a interface precisa num único JSON. */
object Estado {
    fun json(ctx: Context): JSONObject {
        val me = Sessao.eu(ctx)
        val banco = Banco.de(ctx)

        val curtidas = Armazem.curtidas(ctx).groupBy { it.dados.optString("alvo") }
        val coments = Armazem.comentarios(ctx).groupBy { it.dados.optString("alvo") }
        val nomeParc = Sessao.parceiroNome(ctx).ifEmpty { "Parceiro(a)" }

        val lancs = JSONArray()
        Armazem.lancamentos(ctx).take(5000).forEach { l ->
            val cs = curtidas[l.id].orEmpty()
            val cm = JSONArray()
            coments[l.id].orEmpty().sortedBy { it.dados.optLong("q") }.forEach { c ->
                cm.put(JSONObject().put("id", c.id).put("t", c.dados.optString("t")).put("q", c.dados.optLong("q"))
                    .put("a", if (c.dono == me) Ajustes.nome(ctx) else nomeParc).put("meu", c.dono == me))
            }
            lancs.put(JSONObject().put("id", l.id).put("q", l.q).put("v", l.v).put("d", l.d).put("c", l.c)
                .put("t", if (l.receita) "r" else "d").put("sh", l.sh).put("dono", l.dono).put("cf", l.cf)
                .put("l", cs.any { it.dono == me }).put("lc", cs.size).put("cm", cm))
        }

        val hoje = Datas.diaHoje()
        val contas = JSONArray()
        Armazem.contas(ctx).forEach { c ->
            val ocs = Armazem.ocorrenciasComPagamento(ctx, c)
            val arrOcs = JSONArray()
            ocs.forEach { (o, p) -> arrOcs.put(JSONObject().put("chave", o.chave).put("dia", o.data.dayOfMonth).put("n", o.n)
                .put("paga", p != null).put("pagaPor", p?.dono ?: "")) }
            val aberta = ocs.firstOrNull { it.second == null }
            val ref = aberta ?: ocs.lastOrNull()
            contas.put(JSONObject().put("id", c.id).put("n", c.nome).put("v", c.valor).put("dia", c.dia).put("e", c.entrada)
                .put("ini", c.ini).put("vezes", c.vezes).put("var", c.variavel).put("c", c.categoria)
                .put("freq", c.freq).put("d0", c.d0).put("pm", c.pm).put("ocs", arrOcs)
                .put("parcela", ref?.first?.n ?: 0)
                .put("sh", c.sh).put("dono", c.dono)
                .put("paga", ocs.isNotEmpty() && aberta == null)
                .put("pagaPor", ocs.firstNotNullOfOrNull { it.second }?.dono ?: "")
                .put("diasAte", (ref?.first?.data?.dayOfMonth ?: Datas.diaEfetivo(c.dia)) - hoje))
        }

        val metas = JSONArray()
        Armazem.metas(ctx).forEach { m ->
            metas.put(JSONObject().put("id", m.id).put("n", m.nome).put("alvo", m.alvo).put("atual", m.atual).put("e", m.emoji)
                .put("tipo", m.tipo).put("sh", m.sh).put("dono", m.dono))
        }

        val refs = JSONArray()
        Armazem.referencias(ctx).forEach { r ->
            val o = r.dados
            refs.put(JSONObject().put("id", r.id).put("n", o.optString("n")).put("e", o.optString("e")).put("v", o.optDouble("v"))
                .put("dest", o.optBoolean("dest")).put("evitar", o.optBoolean("evitar")).put("m", o.optString("m")))
        }

        val trocas = JSONArray()
        Armazem.trocas(ctx).sortedByDescending { it.dados.optLong("q") }.forEach { t ->
            val o = t.dados
            trocas.put(JSONObject().put("id", t.id).put("q", o.optLong("q")).put("r", o.optString("r")).put("v", o.optDouble("v"))
                .put("n", o.optString("n")).put("e", o.optString("e")).put("m", o.optString("m")))
        }

        val nuvem = JSONObject()
            .put("configurada", Sessao.configurada(ctx))
            .put("logado", Sessao.logado(ctx))
            .put("email", Sessao.email(ctx))
            .put("casal", Sessao.casal(ctx))
            .put("codigo", Sessao.codigo(ctx))
            .put("ultimaMs", Sessao.ultimaSyncMs(ctx))
            .put("pendentes", banco.contarSujos())
            .put("erro", Sessao.erro(ctx))
            .put("convitePendente", Sessao.convitePendente(ctx))
            .put("url", Sessao.url(ctx))

        val parceiro = if (Sessao.parceiroId(ctx).isNotEmpty())
            JSONObject().put("id", Sessao.parceiroId(ctx)).put("nome", nomeParc) else JSONObject.NULL

        return JSONObject()
            .put("eu", JSONObject().put("id", me).put("nome", Ajustes.nome(ctx)))
            .put("parceiro", parceiro)
            .put("nuvem", nuvem)
            .put("moeda", Ajustes.moeda(ctx))
            .put("gastos", lancs)
            .put("contas", contas)
            .put("metas", metas)
            .put("refs", refs)
            .put("trocas", trocas)
            .put("categorias", JSONArray(Categorias.todas))
            .put("categoriasReceita", JSONArray(Categorias.receitas))
            .put("arvore", arvoreJson(ctx))
            .put("hoje", hoje)
            .put("diasNoMes", Datas.diasNoMes())
            .put("conquistasVistas", Ajustes.conquistasVistas(ctx))
            .put("notif", Ajustes.notifAtiva(ctx))
            .put("agitar", Ajustes.agitarAtivo(ctx))
            .put("sens", Ajustes.sensibilidade(ctx))
            .put("premium", Armazem.premium(ctx))
            .put("pro", JSONObject().put("servidor", Sessao.proServidor(ctx)).put("admin", Sessao.proAdmin(ctx))
                .put("expiraMs", Sessao.proExpira(ctx)).put("pendente", Sessao.proPendente(ctx)))
            .put("bateriaLivre", Agitar.bateriaLivre(ctx))
            .put("atualizacao", Atualizador.estado(ctx))
            .put("servidorProprio", Sessao.url(ctx) != Sessao.URL_PADRAO)
            .put("versao", try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" })
    }
}

/** Árvore de categorias para a interface: as do app e as criadas pela pessoa (com id, para poder apagar). */
fun arvoreJson(ctx: Context): JSONArray {
    val proprias = Armazem.categoriasProprias(ctx)
    val arr = JSONArray()
    val pais = LinkedHashMap<String, JSONObject>()
    Categorias.arvore.forEach { c ->
        val subs = JSONArray()
        c.subs.forEach { subs.put(JSONObject().put("n", it.n).put("e", it.e)) }
        val o = JSONObject().put("n", c.n).put("e", c.e).put("cor", c.cor).put("receita", c.receita).put("subs", subs)
        pais[c.n] = o; arr.put(o)
    }
    // primeiro as categorias principais criadas, depois as subcategorias
    proprias.sortedBy { if (it.dados.optString("pai").isEmpty()) 0 else 1 }.forEach { r ->
        val d = r.dados; val pai = d.optString("pai")
        if (pai.isEmpty()) {
            if (pais.containsKey(d.optString("n"))) return@forEach
            val o = JSONObject().put("n", d.optString("n")).put("e", d.optString("e", "🏷️")).put("cor", d.optString("cor", "#C9C2DA"))
                .put("receita", false).put("subs", JSONArray()).put("id", r.id)
            pais[d.optString("n")] = o; arr.put(o)
        } else pais[pai]?.getJSONArray("subs")?.put(JSONObject().put("n", d.optString("n")).put("e", d.optString("e", "🏷️")).put("id", r.id))
    }
    return arr
}

/** O que cada tela nativa oferece à interface. */
interface Hospedeiro {
    fun vibrar()
    fun ativarNotificacao()
    fun ativarAgitar(ativo: Boolean)
    fun abrirAjustesPopup()
    fun abrirAjustesApp()
    fun fechar()
    fun avisar(msg: String)
    fun exportar()
    fun importar()
    fun ouvir()
    fun pararDeOuvir()
    /** Escolher um PDF ou foto de fatura no celular. */
    fun escolherFatura()
    fun compartilhar(texto: String)
    fun liberarBateria()
    fun permitirInstalacao()
    fun abrirInicioAutomatico()
    /** Executa um trecho de JavaScript na interface (usado para responder operações demoradas). */
    fun js(codigo: String)
}

/** Ponte entre a interface (HTML/JS) e os dados nativos. Exposta no JS como window.App. */
class Ponte(private val host: Hospedeiro, contexto: Context) {
    private val ctx = contexto.applicationContext

    private fun resp(ok: Boolean, msg: String = ""): String =
        JSONObject().put("ok", ok).put("msg", msg).put("estado", Estado.json(ctx)).toString()

    /** Mantém a notificação fixa em dia depois de qualquer mudança. */
    private fun atualizarNotif() { if (Ajustes.notifAtiva(ctx) || Ajustes.agitarAtivo(ctx)) Notificacao.mostrar(ctx) }

    private fun mudar(bloco: () -> Unit): String { bloco(); atualizarNotif(); return resp(true) }

    /** Operações de rede: rodam fora da tela e respondem chamando window.respostaNuvem(id, json). */
    private fun emSegundoPlano(pedido: String, bloco: () -> JSONObject) {
        Nuvem.executar {
            val r = try { bloco() } catch (e: Exception) { JSONObject().put("ok", false).put("msg", e.message ?: "Erro") }
            r.put("estado", Estado.json(ctx))
            host.js("window.respostaNuvem && respostaNuvem(" + JSONObject.quote(pedido) + "," + r.toString() + ")")
        }
    }

    @JavascriptInterface fun estado(): String = Estado.json(ctx).toString()

    // ---------- Lançamentos ----------
    /** quando = data em milissegundos ("" = agora). */
    @JavascriptInterface fun adicionarGasto(texto: String, categoria: String, receita: Boolean, sh: Boolean, quando: String): String {
        var g = Interpretador.interpretar(texto, receita) ?: return resp(false)
        if (categoria.isNotBlank()) g = g.copy(categoria = categoria)
        quando.toLongOrNull()?.let { g = g.copy(quando = it) }
        return mudar { Armazem.adicionar(ctx, g, sh) }
    }

    @JavascriptInterface fun detectar(texto: String, receita: Boolean): String {
        val g = Interpretador.interpretar(texto, receita) ?: return ""
        return JSONObject().put("c", g.categoria).put("r", g.receita).put("v", g.valor)
            .put("e", g.categoriaEscrita).put("d", g.descricao).toString()
    }

    @JavascriptInterface fun editarGasto(id: String, q: String, valor: String, desc: String, cat: String, receita: Boolean, sh: Boolean): String {
        val v = Interpretador.paraNumero(valor)
        if (v == null || v <= 0 || desc.isBlank()) return resp(false)
        return mudar { Armazem.editar(ctx, id, Gasto(q.toLong(), v, desc.trim(), cat, receita), sh) }
    }

    /** Muda a conta que se repete a partir de agora (dia = 0 mantém o dia). */
    @JavascriptInterface fun editarConta(id: String, nome: String, valor: String, cat: String, dia: Int): String {
        val v = Interpretador.paraNumero(valor)
        if (v == null || v <= 0 || nome.isBlank()) return resp(false)
        return mudar { Armazem.editarConta(ctx, id, nome.trim(), v, cat, dia) }
    }

    /** Muda a conta inteira, inclusive a forma de repetir (ver Armazem.redefinirConta). desde = yyyy-MM-dd. */
    @JavascriptInterface fun redefinirConta(id: String, nome: String, valor: String, cat: String, desde: String,
                                            freq: String, pm: Int, vezes: Int, variavel: Boolean): String {
        val v = Interpretador.paraNumero(valor)
        if (v == null || v <= 0 || nome.isBlank()) return resp(false, "Confira o nome e o valor")
        val d = try { java.time.LocalDate.parse(desde) } catch (e: Exception) { return resp(false, "Confira a data") }
        if (vezes < 0 || vezes > 600) return resp(false, "Confira o número de vezes")
        if (vezes != 1 && !Armazem.premium(ctx)) return resp(false, "Repetir contas é um recurso Premium")
        return mudar { Armazem.redefinirConta(ctx, id, nome.trim(), v, cat, freq, pm, d, vezes, variavel) }
    }

    @JavascriptInterface fun duplicarGasto(id: String): String = mudar { Armazem.duplicar(ctx, id) }
    @JavascriptInterface fun apagar(id: String): String = mudar { Armazem.apagar(ctx, id) }
    @JavascriptInterface fun restaurar(id: String): String = mudar { Armazem.restaurar(ctx, id) }
    @JavascriptInterface fun mudarCategoria(id: String, cat: String): String = mudar { Armazem.mudarCategoria(ctx, id, cat) }
    @JavascriptInterface fun curtir(id: String): String { Armazem.curtir(ctx, id); return resp(true) }

    @JavascriptInterface fun comentar(id: String, texto: String): String {
        if (texto.isBlank()) return resp(false)
        Armazem.comentar(ctx, id, texto.trim()); return resp(true)
    }

    // ---------- Contas e entradas fixas ----------
    /**
     * Lançamento que se repete ou acontece noutra data. texto = "400 renda"; ini = yyyy-MM; vezes = 0 (todo mês), 1 (data única) ou N.
     * pagoJa = o deste mês já foi pago/recebido (cria o lançamento agora).
     */
    /**
     * Conta que se repete ou fica programada. d0 = primeira data (yyyy-MM-dd); freq = "m", "q" ou "s";
     * vezes = 0 (sem fim), 1 (data única) ou N. pagoJa = a primeira já foi paga/recebida.
     * Repetir (vezes != 1) é recurso Premium; uma data única é livre.
     */
    @JavascriptInterface fun adicionarRecorrente(texto: String, categoria: String, receita: Boolean, sh: Boolean,
                                                 d0: String, freq: String, vezes: Int, variavel: Boolean, pagoJa: Boolean): String {
        if (vezes != 1 && !Armazem.premium(ctx)) return resp(false, "Repetir contas é um recurso Premium")
        val g = Interpretador.interpretar(texto, receita) ?: return resp(false, "Escreva o valor e o que é, como “400 renda”")
        val data = try { java.time.LocalDate.parse(d0) } catch (e: Exception) { return resp(false, "Confira a data") }
        if (vezes < 0 || vezes > 600) return resp(false, "Confira o número de vezes")
        val f = if (freq == "q" || freq == "s") freq else "m"
        val nome = g.descricao.replaceFirstChar { it.uppercase() }
        val cat = categoria.ifBlank { if (receita) g.categoria else g.categoria.takeIf { it != Categorias.OUTROS } ?: "" }
        val ini = String.format("%04d-%02d", data.year, data.monthValue)
        val compartilhar = sh && Armazem.premium(ctx)
        val id = Armazem.adicionarConta(ctx, nome, g.valor, data.dayOfMonth, receita, compartilhar, ini, vezes, variavel, cat, f, if (f == "m") "" else d0)
        if (pagoJa && !data.isAfter(java.time.LocalDate.now()) && ini == Datas.mesAtual())
            Armazem.alternarPaga(ctx, id, null, if (f == "m") ini else d0)
        atualizarNotif()
        return resp(true)
    }

    /**
     * Fatura confirmada na folha. json: {nome, valor, data (yyyy-MM-dd), pm (0 = só esta; 1, 2, 3, 6, 12 meses),
     * vezes (0 = sem fim), parcela (n.º desta, se for prestação), variavel, paga, cat, sh, contaId (conta já existente)}.
     */
    @JavascriptInterface fun adicionarFatura(json: String): String {
        val o = try { JSONObject(json) } catch (e: Exception) { return resp(false, "Dados inválidos") }
        val valor = Interpretador.paraNumero(o.optString("valor"))?.takeIf { it > 0 } ?: return resp(false, "Confira o valor")
        var data = try { java.time.LocalDate.parse(o.optString("data")) } catch (e: Exception) { return resp(false, "Confira a data") }
        val hoje = java.time.LocalDate.now()
        val paga = o.optBoolean("paga")
        val quando = { d: java.time.LocalDate ->
            if (d.isAfter(hoje)) System.currentTimeMillis()
            else d.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val mesDe = { d: java.time.LocalDate -> String.format("%04d-%02d", d.year, d.monthValue) }

        // Fatura de uma conta que já existe: atualiza o valor (se varia) e marca como paga
        val contaId = o.optString("contaId")
        if (contaId.isNotEmpty()) {
            val c = Armazem.contas(ctx).firstOrNull { it.id == contaId } ?: return resp(false, "Conta não encontrada")
            if (c.variavel && c.dono == Armazem.eu(ctx)) Armazem.mudarValorConta(ctx, c.id, valor)
            if (paga) {
                val chave = if (c.freq == "m") mesDe(data) else data.toString()
                val oc = c.ocorrenciasNoMes(data.year, data.monthValue).firstOrNull { it.chave == chave }
                Armazem.pagarOcorrencia(ctx, c, chave, oc?.n ?: 1, quando(data), valor)
            }
            atualizarNotif()
            return resp(true, "Fatura registada em ${c.nome}")
        }

        val nome = o.optString("nome").trim().ifEmpty { "Fatura" }
        val pm = o.optInt("pm", 0)
        val repete = pm > 0
        if (repete && !Armazem.premium(ctx)) return resp(false, "Contas que se repetem são um recurso Premium")
        var vezes = if (repete) maxOf(0, o.optInt("vezes", 0)) else 1
        val parcela = if (repete && vezes > 0) o.optInt("parcela", 0).coerceIn(0, vezes) else 0
        // uma fatura única em atraso de meses anteriores aparece como a pagar hoje
        if (!repete && !paga && data.isBefore(hoje.withDayOfMonth(1))) data = hoje
        // prestação 12 de 48: a conta começa na 1.ª, para esta ser a 12.ª
        val inicio = if (parcela > 1) data.minusMonths(((parcela - 1) * pm).toLong()) else data
        val c0 = ContaFixa("", nome, valor, data.dayOfMonth, false, false, "", mesDe(inicio), vezes, false, "", "m", "", maxOf(1, pm))
        val id = Armazem.adicionarConta(ctx, nome, valor, data.dayOfMonth, false, o.optBoolean("sh") && Armazem.premium(ctx),
            mesDe(inicio), vezes, o.optBoolean("variavel") && repete, o.optString("cat"), "m", "", maxOf(1, pm))
        if (paga) {
            val c = Armazem.contas(ctx).firstOrNull { it.id == id }
            if (c != null) Armazem.pagarOcorrencia(ctx, c, mesDe(data), c0.parcelaEm(mesDe(data)) ?: 1, quando(data), valor)
        }
        atualizarNotif()
        val oque = when {
            !repete -> "Fatura adicionada"
            vezes > 0 -> "Conta criada: ${vezes - maxOf(parcela, 1) + 1} de $vezes prestações pela frente"
            else -> "Conta criada: " + mapOf(1 to "todo mês", 2 to "a cada 2 meses", 3 to "a cada 3 meses", 6 to "a cada 6 meses", 12 to "todo ano").getOrElse(pm) { "a cada $pm meses" }
        }
        return resp(true, oque)
    }

    /** Nova categoria criada pela pessoa. pai = "" para uma categoria principal. */
    @JavascriptInterface fun salvarCategoria(nome: String, pai: String, emoji: String, cor: String): String {
        val n = nome.trim().replace(Categorias.SEP.trim(), "-").take(40)
        if (n.isEmpty()) return resp(false, "Escreva o nome da categoria")
        val caminho = if (pai.isEmpty()) n else pai + Categorias.SEP + n
        if (Categorias.normalizar(caminho) in (Categorias.todas + Categorias.receitas).map { Categorias.normalizar(it) }) return resp(false, "Essa categoria já existe")
        return mudar { Armazem.salvarCategoria(ctx, n, pai, emoji.ifBlank { "🏷️" }, cor.ifBlank { "#C9C2DA" }) }
    }

    @JavascriptInterface fun copiar(texto: String): String {
        ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Finanças", texto))
        return resp(true, "Copiado")
    }

    @JavascriptInterface fun escolherFatura() { host.escolherFatura() }

    /** Feedback fica como um registo privado "feedback" no servidor (lido pelo responsável do app no Supabase). */
    @JavascriptInterface fun enviarFeedback(tipo: String, texto: String): String {
        if (texto.isBlank()) return resp(false, "Escreva a sua mensagem")
        val versao = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        Armazem.registrarFeedback(ctx, JSONObject().put("tipo", tipo).put("t", texto.trim().take(4000)).put("q", System.currentTimeMillis())
            .put("versao", versao).put("aparelho", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .put("sistema", "Android " + android.os.Build.VERSION.RELEASE))
        return resp(true)
    }

    // ---------- Pro por pedido e painel admin (servidor) ----------
    private val funcoesPro = setOf("pedir_pro", "cancelar_meu_pedido", "resgatar_codigo", "admin_listar_pedidos", "admin_listar_usuarios",
        "aprovar_pedido", "recusar_pedido", "admin_conceder_dias", "admin_revogar_pro", "admin_gerar_codigo")

    /** args = objeto JSON com os parâmetros da função; a resposta traz "dados" (o JSON devolvido, em texto). */
    @JavascriptInterface fun pro(pedido: String, funcao: String, args: String) = emSegundoPlano(pedido) {
        if (funcao !in funcoesPro) return@emSegundoPlano JSONObject().put("ok", false).put("msg", "Função desconhecida")
        val a = try { JSONObject(args) } catch (e: Exception) { JSONObject() }
        val r = Nuvem.chamarPro(ctx, funcao, a)
        if (r.ok) Nuvem.atualizarPro(ctx)
        JSONObject().put("ok", r.ok).put("msg", if (r.ok) "" else r.mensagem()).put("dados", r.corpo)
    }

    @JavascriptInterface fun atualizarPro(pedido: String) = emSegundoPlano(pedido) { Nuvem.atualizarPro(ctx); JSONObject().put("ok", true) }

    @JavascriptInterface fun lerConfig(pedido: String) = emSegundoPlano(pedido) {
        val r = Nuvem.lerConfig(ctx)
        JSONObject().put("ok", r.ok).put("msg", if (r.ok) "" else r.mensagem()).put("dados", r.corpo)
    }

    /** valor = JSON em texto (objeto, lista, número ou texto entre aspas). */
    @JavascriptInterface fun salvarConfig(pedido: String, chave: String, valor: String) = emSegundoPlano(pedido) {
        val v: Any = try { org.json.JSONTokener(valor).nextValue() } catch (e: Exception) { return@emSegundoPlano JSONObject().put("ok", false).put("msg", "JSON inválido") }
        val r = Nuvem.salvarConfig(ctx, chave, v)
        JSONObject().put("ok", r.ok).put("msg", if (r.ok) "Configuração salva" else if (r.codigo == 401 || r.codigo == 403) "Só o admin pode alterar" else r.mensagem())
    }

    @JavascriptInterface fun definirPremium(ativo: Boolean): String = mudar { Armazem.definirPremium(ctx, ativo) }

    @JavascriptInterface fun alternarPaga(id: String, valorReal: String, chave: String): String {
        val ok = Armazem.alternarPaga(ctx, id, Interpretador.paraNumero(valorReal)?.takeIf { it > 0 }, chave)
        atualizarNotif()
        return resp(ok, if (ok) "" else "Só quem marcou pode desmarcar")
    }

    // ---------- Destinos ----------
    @JavascriptInterface fun adicionarMeta(nome: String, alvo: String, emoji: String, tipo: String, sh: Boolean): String {
        val a = Interpretador.paraNumero(alvo)
        if (nome.isBlank() || a == null || a <= 0) return resp(false)
        return mudar { Armazem.adicionarMeta(ctx, nome.trim(), a, emoji.ifBlank { "🎯" }, tipo, sh) }
    }

    @JavascriptInterface fun aportarMeta(id: String, valor: String): String {
        val v = Interpretador.paraNumero(valor.replace("-", "")) ?: return resp(false)
        val sinal = if (valor.trim().startsWith("-")) -1 else 1
        return resp(Armazem.aportar(ctx, id, v * sinal))
    }

    // ---------- Referências e trocas ----------
    @JavascriptInterface fun salvarReferencia(id: String, nome: String, emoji: String, valor: String, destaque: Boolean, evitar: Boolean, meta: String): String {
        val v = Interpretador.paraNumero(valor)
        if (nome.isBlank() || v == null || v <= 0) return resp(false)
        return mudar { Armazem.salvarReferencia(ctx, id, nome.trim(), emoji.ifBlank { "⭐" }, v, destaque, evitar, meta) }
    }

    @JavascriptInterface fun evitei(id: String): String {
        val msg = Armazem.registrarTroca(ctx, id) ?: return resp(false)
        atualizarNotif()
        return resp(true, msg)
    }

    @JavascriptInterface fun apagarTroca(id: String, restaurar: Boolean): String = mudar { Armazem.apagarTroca(ctx, id, restaurar) }

    // ---------- Conta e casal (rede) ----------
    @JavascriptInterface fun configurarServidor(url: String, chave: String): String {
        val e = Nuvem.configurar(ctx, url, chave)
        return resp(e == null, e ?: "")
    }

    @JavascriptInterface fun usarConvite(texto: String): String {
        val e = Nuvem.lerConvite(ctx, texto)
        if (e == null && Sessao.logado(ctx)) Nuvem.agendar(ctx, 0)
        return resp(e == null, e ?: "")
    }

    @JavascriptInterface fun entrarConta(email: String, senha: String, nome: String, criar: Boolean) = emSegundoPlano("entrar") {
        val e = Nuvem.entrar(ctx, email, senha, criar, nome)
        if (e == null && nome.isNotBlank()) Nuvem.definirNome(ctx, nome.trim())
        JSONObject().put("ok", e == null).put("msg", e ?: "")
    }

    @JavascriptInterface fun recuperarSenha(email: String) = emSegundoPlano("recuperar") {
        val e = Nuvem.recuperarSenha(ctx, email)
        JSONObject().put("ok", e == null).put("msg", e ?: "")
    }

    @JavascriptInterface fun redefinirSenha(email: String, codigo: String, nova: String) = emSegundoPlano("redefinir") {
        val e = Nuvem.redefinirSenha(ctx, email, codigo, nova)
        JSONObject().put("ok", e == null).put("msg", e ?: "")
    }

    @JavascriptInterface fun trocarSenha(nova: String) = emSegundoPlano("trocarSenha") {
        val e = Nuvem.trocarSenha(ctx, nova)
        JSONObject().put("ok", e == null).put("msg", e ?: "Senha alterada")
    }

    @JavascriptInterface fun testarServidor() = emSegundoPlano("testar") {
        val e = Nuvem.testar(ctx)
        JSONObject().put("ok", e == null).put("msg", e ?: "Ligação ao servidor funcionando")
    }

    @JavascriptInterface fun convidar() = emSegundoPlano("convidar") {
        val (texto, e) = Nuvem.convidar(ctx)
        JSONObject().put("ok", texto != null).put("msg", e ?: "").put("texto", texto ?: "")
    }

    @JavascriptInterface fun sairDoCasal() = emSegundoPlano("sairCasal") {
        val e = Nuvem.sairDoCasal(ctx)
        JSONObject().put("ok", e == null).put("msg", e ?: "")
    }

    @JavascriptInterface fun sincronizar() = emSegundoPlano("sincronizar") {
        val e = Nuvem.sincronizarAgora(ctx)
        JSONObject().put("ok", e == null).put("msg", e ?: "")
    }

    @JavascriptInterface fun sairConta(): String { Nuvem.sair(ctx); return resp(true) }
    @JavascriptInterface fun compartilhar(texto: String) { host.compartilhar(texto) }
    @JavascriptInterface fun liberarBateria() { host.liberarBateria() }
    @JavascriptInterface fun permitirInstalacao() { host.permitirInstalacao() }

    // ---------- Atualizações ----------
    @JavascriptInterface fun procurarAtualizacao() = emSegundoPlano("procurarAtualizacao") {
        val nova = Atualizador.verificar(ctx)
        val erro = Atualizador.estado(ctx).optString("erro")
        JSONObject().put("ok", erro.isEmpty()).put("msg", if (erro.isNotEmpty()) erro else if (nova) "" else "Você já tem a versão mais recente")
            .put("nova", nova)
    }

    @JavascriptInterface fun instalarAtualizacao() = emSegundoPlano("instalarAtualizacao") {
        val e = Atualizador.baixarEInstalar(ctx)
        JSONObject().put("ok", e == null).put("msg", e ?: "")
    }

    @JavascriptInterface fun definirAutoAtualizar(v: Boolean): String { Atualizador.definirAutomatico(ctx, v); return resp(true) }
    @JavascriptInterface fun abrirInicioAutomatico() { host.abrirInicioAutomatico() }

    /** Copia o script do banco (assets/esquema.sql) para quem quer usar o próprio Supabase. */
    @JavascriptInterface fun copiarEsquema(): String {
        val sql = ctx.assets.open("esquema.sql").bufferedReader().use { it.readText() }
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("esquema", sql))
        return resp(true, "Script copiado")
    }

    @JavascriptInterface fun servidorPadrao(): String {
        if (Sessao.url(ctx) != Sessao.URL_PADRAO) { Nuvem.sair(ctx); Sessao.editar(ctx) { remove("url"); remove("chave") } }
        return resp(true)
    }

    // ---------- Perfil e ajustes ----------
    @JavascriptInterface fun salvarNome(nome: String): String {
        Ajustes.salvarNome(ctx, nome.trim())
        Nuvem.definirNome(ctx, nome.trim())
        return resp(true)
    }

    @JavascriptInterface fun salvarMoeda(codigo: String): String = mudar { Ajustes.salvarMoeda(ctx, codigo) }

    @JavascriptInterface fun definirSensibilidade(n: Int): String {
        Ajustes.definirSensibilidade(ctx, n)
        Agitar.sincronizar(ctx)
        return resp(true)
    }

    @JavascriptInterface fun testarPopup() { Notificacao.popup(ctx) }
    @JavascriptInterface fun marcarConquista(id: String) { Ajustes.marcarConquista(ctx, id) }

    @JavascriptInterface fun ativarNotificacao() { host.ativarNotificacao() }
    @JavascriptInterface fun ativarAgitar(ativo: Boolean) { host.ativarAgitar(ativo) }
    @JavascriptInterface fun abrirAjustesPopup() { host.abrirAjustesPopup() }
    @JavascriptInterface fun abrirAjustesApp() { host.abrirAjustesApp() }
    @JavascriptInterface fun vibrar() { host.vibrar() }
    @JavascriptInterface fun fechar() { host.fechar() }
    @JavascriptInterface fun avisar(msg: String) { host.avisar(msg) }
    @JavascriptInterface fun exportar() { host.exportar() }
    @JavascriptInterface fun importar() { host.importar() }
    @JavascriptInterface fun ouvir() { host.ouvir() }
    @JavascriptInterface fun pararDeOuvir() { host.pararDeOuvir() }
}
