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
            val quem = Armazem.quemPagou(ctx, c.id)
            contas.put(JSONObject().put("id", c.id).put("n", c.nome).put("v", c.valor).put("dia", c.dia).put("e", c.entrada)
                .put("ini", c.ini).put("vezes", c.vezes).put("var", c.variavel).put("c", c.categoria)
                .put("parcela", c.parcelaEm(Datas.mesAtual()) ?: 0)
                .put("sh", c.sh).put("dono", c.dono).put("paga", quem != null).put("pagaPor", quem ?: "")
                .put("diasAte", Datas.diaEfetivo(c.dia) - hoje))
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
            .put("hoje", hoje)
            .put("diasNoMes", Datas.diasNoMes())
            .put("conquistasVistas", Ajustes.conquistasVistas(ctx))
            .put("notif", Ajustes.notifAtiva(ctx))
            .put("agitar", Ajustes.agitarAtivo(ctx))
            .put("sens", Ajustes.sensibilidade(ctx))
            .put("bateriaLivre", Agitar.bateriaLivre(ctx))
            .put("atualizacao", Atualizador.estado(ctx))
            .put("servidorProprio", Sessao.url(ctx) != Sessao.URL_PADRAO)
            .put("versao", try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" })
    }
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
        return JSONObject().put("c", g.categoria).put("r", g.receita).put("v", g.valor).toString()
    }

    @JavascriptInterface fun editarGasto(id: String, q: String, valor: String, desc: String, cat: String, receita: Boolean, sh: Boolean): String {
        val v = Interpretador.paraNumero(valor)
        if (v == null || v <= 0 || desc.isBlank()) return resp(false)
        return mudar { Armazem.editar(ctx, id, Gasto(q.toLong(), v, desc.trim(), cat, receita), sh) }
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
    @JavascriptInterface fun adicionarRecorrente(texto: String, categoria: String, receita: Boolean, sh: Boolean,
                                                 dia: Int, ini: String, vezes: Int, variavel: Boolean, pagoJa: Boolean): String {
        val g = Interpretador.interpretar(texto, receita) ?: return resp(false, "Escreva o valor e o que é, como “400 renda”")
        if (dia !in 1..31 || vezes < 0 || vezes > 600 || !Regex("^\\d{4}-\\d{2}$").matches(ini)) return resp(false, "Confira a data e o número de vezes")
        val nome = g.descricao.replaceFirstChar { it.uppercase() }
        val cat = categoria.ifBlank { if (receita) g.categoria else g.categoria.takeIf { it != Categorias.OUTROS } ?: "" }
        val id = Armazem.adicionarConta(ctx, nome, g.valor, dia, receita, sh, ini, vezes, variavel, cat)
        if (pagoJa && ini <= Datas.mesAtual()) Armazem.contasDoMes(ctx).firstOrNull { it.id == id }?.let { Armazem.alternarPaga(ctx, id) }
        atualizarNotif()
        return resp(true)
    }

    @JavascriptInterface fun alternarPaga(id: String, valorReal: String): String {
        val ok = Armazem.alternarPaga(ctx, id, Interpretador.paraNumero(valorReal)?.takeIf { it > 0 })
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
}
