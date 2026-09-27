package com.pessoal.financas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Conta e ligação com o servidor (Supabase), guardadas só neste aparelho. */
object Sessao {
    private fun p(ctx: Context) = ctx.getSharedPreferences("nuvem", Context.MODE_PRIVATE)

    // Servidor do casal, já embutido: a chave "anon" é pública por natureza (quem protege os dados são as regras de acesso)
    const val URL_PADRAO = "https://yalkbujtjlrryttmccrw.supabase.co"
    const val CHAVE_PADRAO = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlhbGtidWp0amxycnl0dG1jY3J3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTA0NDA5NDgsImV4cCI6MjEwNjAxNjk0OH0.zVc8dd2SflgXQRFUPskwvcdHLHYys_sZIitGSqe8IYo"

    /** Na primeira abertura desta versão, descarta um servidor colado à mão (pode ter vindo com erro). */
    private fun garantirPadrao(ctx: Context) {
        val pr = p(ctx)
        if (!pr.getBoolean("padrao_v1", false)) pr.edit().remove("url").remove("chave").putBoolean("padrao_v1", true).commit()
    }

    fun url(ctx: Context): String { garantirPadrao(ctx); return (p(ctx).getString("url", "") ?: "").ifEmpty { URL_PADRAO } }
    fun chave(ctx: Context): String { garantirPadrao(ctx); return (p(ctx).getString("chave", "") ?: "").ifEmpty { CHAVE_PADRAO } }
    fun configurada(ctx: Context) = url(ctx).isNotEmpty() && chave(ctx).isNotEmpty()

    fun uid(ctx: Context): String? = p(ctx).getString("uid", null)
    fun logado(ctx: Context) = uid(ctx) != null
    /** Dono dos registros criados neste aparelho: a conta, ou "local" enquanto não houver conta. */
    fun eu(ctx: Context): String = uid(ctx) ?: "local"
    fun email(ctx: Context): String = p(ctx).getString("email", "") ?: ""

    fun casal(ctx: Context): String = p(ctx).getString("casal", "") ?: ""
    fun codigo(ctx: Context): String = p(ctx).getString("codigo", "") ?: ""
    fun parceiroId(ctx: Context): String = p(ctx).getString("parceiroId", "") ?: ""
    fun parceiroNome(ctx: Context): String = p(ctx).getString("parceiroNome", "") ?: ""
    fun ultima(ctx: Context): String = p(ctx).getString("ultima", "") ?: ""
    fun ultimaSyncMs(ctx: Context): Long = p(ctx).getLong("ultimaMs", 0L)
    fun erro(ctx: Context): String = p(ctx).getString("erro", "") ?: ""
    fun convitePendente(ctx: Context): String = p(ctx).getString("convitePendente", "") ?: ""

    fun editar(ctx: Context, bloco: android.content.SharedPreferences.Editor.() -> Unit) { p(ctx).edit().apply(bloco).commit() }

    fun sair(ctx: Context) = editar(ctx) {
        listOf("uid", "email", "access", "refresh", "expira", "casal", "codigo", "parceiroId", "parceiroNome", "ultima", "ultimaMs", "erro", "convitePendente").forEach { remove(it) }
    }

    fun access(ctx: Context): String = p(ctx).getString("access", "") ?: ""
    fun refresh(ctx: Context): String = p(ctx).getString("refresh", "") ?: ""
    fun expira(ctx: Context): Long = p(ctx).getLong("expira", 0L)
}

/** Resposta HTTP simples. */
data class Resp(val codigo: Int, val corpo: String) {
    val ok get() = codigo in 200..299
    fun json(): JSONObject = try { JSONObject(corpo) } catch (e: Exception) { JSONObject() }
    /** Mensagem de erro legível vinda do Supabase. */
    fun mensagem(): String {
        val j = json()
        val m = j.optString("msg").ifEmpty { j.optString("error_description") }.ifEmpty { j.optString("message") }.ifEmpty { j.optString("error") }
        return when {
            codigo == 0 -> motivoFalha(corpo)
            m.contains("Invalid login", true) -> "E-mail ou senha incorretos"
            m.contains("already registered", true) -> "Este e-mail já tem conta. Use Entrar."
            m.contains("Email not confirmed", true) -> "Confirme o e-mail (veja a sua caixa de entrada) e tente de novo"
            m.contains("Password should", true) -> "A senha precisa de pelo menos 6 caracteres"
            m.isNotEmpty() -> m
            else -> "Erro $codigo"
        }
    }
}

/** Traduz a exceção de rede na causa provável. */
fun motivoFalha(detalhe: String): String = when {
    detalhe.startsWith("UnknownHost") -> "Servidor não encontrado. Confira o Project URL (deve ser https://xxxx.supabase.co) ou se há internet."
    detalhe.startsWith("SSL") || detalhe.contains("Certificate", true) -> "Falha de segurança na ligação. Confira se a data e a hora do celular estão certas."
    detalhe.startsWith("SocketTimeout") -> "O servidor demorou a responder. Tente de novo; se o projeto do Supabase estava pausado, ele acorda em 1 a 2 minutos."
    detalhe.startsWith("Connect") || detalhe.contains("ENETUNREACH") || detalhe.contains("Network is unreachable") -> "Sem acesso à internet para o app. Veja em Definições → Apps → Finanças → Uso de dados se Wi-Fi e dados móveis estão permitidos."
    detalhe.startsWith("SecurityException") || detalhe.contains("permission", true) -> "O sistema bloqueou o acesso à internet do app. Verifique as permissões de rede do Finanças."
    detalhe.startsWith("IllegalArgument") || detalhe.startsWith("MalformedURL") -> "Endereço ou chave com caracteres inválidos. Ligue o servidor de novo, colando só o Project URL e a chave."
    else -> "Falha de ligação ($detalhe)"
}

object Http {
    fun enviar(metodo: String, url: String, cabecalhos: Map<String, String>, corpo: String? = null): Resp = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        c.connectTimeout = 15000
        c.readTimeout = 20000
        cabecalhos.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (corpo != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(corpo.toByteArray()) }
        }
        val cod = c.responseCode
        val txt = (if (cod in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        Resp(cod, txt)
    } catch (e: Exception) {
        Resp(0, e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
}

/** Login, casal e sincronização com o Supabase. Tudo roda fora da tela, numa fila única. */
object Nuvem {
    private val fila = Executors.newSingleThreadScheduledExecutor()
    private var agendada: ScheduledFuture<*>? = null
    private val ouvintes = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    @Volatile var telaVisivel = false

    fun aoMudar(f: () -> Unit) { ouvintes.add(f) }
    fun remover(f: () -> Unit) { ouvintes.remove(f) }
    private fun avisarTelas() = ouvintes.forEach { it() }

    private fun base(ctx: Context) = Sessao.url(ctx).trimEnd('/')
    private fun cab(ctx: Context, token: String? = null): Map<String, String> {
        val chave = Sessao.chave(ctx)
        val m = hashMapOf("apikey" to chave, "Accept" to "application/json")
        // As chaves novas (sb_publishable_...) não vão no Authorization; as antigas (anon, formato eyJ...) podem ir
        when {
            token != null -> m["Authorization"] = "Bearer $token"
            chave.startsWith("eyJ") -> m["Authorization"] = "Bearer $chave"
        }
        return m
    }

    // ---------- Configuração do servidor ----------
    fun configurar(ctx: Context, url: String, chave: String): String? {
        var u = url.filter { it.code in 33..126 }.trimEnd('/')
        // Aceita também o endereço do painel (supabase.com/dashboard/project/<id>/...) e converte
        Regex("supabase\\.com/dashboard/project/([a-z0-9]{10,})").find(u)?.let { u = "https://${it.groupValues[1]}.supabase.co" }
        if (Regex("^[a-z0-9]{15,30}$").matches(u)) u = "https://$u.supabase.co"
        if (!u.startsWith("http")) u = "https://$u"
        // corta caminhos colados por engano (…supabase.co/rest/v1/)
        Regex("^(https?://[^/]+)").find(u)?.let { u = it.groupValues[1] }
        u = u.replace("http://", "https://")
        if (!u.startsWith("https://") || u.length < 12) return "Endereço inválido: use o Project URL, como https://xxxx.supabase.co"
        val k = chave.filter { it.code in 33..126 }   // remove espaços, quebras e caracteres invisíveis
        if (k.startsWith("sb_secret_")) return "Essa é a chave secreta. Use a chave publishable (sb_publishable_...) ou a anon."
        if (k.startsWith("eyJ")) {
            val papel = try { String(Base64.decode(k.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)) } catch (e: Exception) { "" }
            if (papel.contains("service_role")) return "Essa é a chave service_role (secreta). Use a chave anon public."
        }
        if (k.length < 30) return "Chave incompleta: copie a chave inteira (publishable ou anon)"
        if (Sessao.logado(ctx) && u != Sessao.url(ctx)) sair(ctx)
        Sessao.editar(ctx) { putString("url", u); putString("chave", k) }
        return null
    }

    /** Convite = endereço do servidor + chave pública + código do casal, num texto só para colar. */
    fun textoConvite(ctx: Context, codigo: String): String {
        val j = JSONObject().put("u", Sessao.url(ctx)).put("k", Sessao.chave(ctx)).put("c", codigo)
        val b64 = Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        return "Convite para o app Finanças (código $codigo):\nFINANCAS-$b64"
    }

    /** Lê um convite colado. Se o servidor ainda não estiver configurado, configura. */
    fun lerConvite(ctx: Context, texto: String): String? {
        val m = Regex("FINANCAS-([A-Za-z0-9_\\-=]+)").find(texto)
        if (m != null) {
            val j = try { JSONObject(String(Base64.decode(m.groupValues[1], Base64.URL_SAFE))) } catch (e: Exception) { return "Convite inválido" }
            configurar(ctx, j.optString("u"), j.optString("k"))?.let { return it }
            Sessao.editar(ctx) { putString("convitePendente", j.optString("c")) }
            return null
        }
        val codigo = texto.trim().uppercase()
        if (Regex("^[A-F0-9]{6}$").matches(codigo)) {
            if (!Sessao.configurada(ctx)) return "Cole o convite completo (o texto que começa com FINANCAS-)"
            Sessao.editar(ctx) { putString("convitePendente", codigo) }
            return null
        }
        return "Convite inválido"
    }

    /** Verifica endereço e chave sem precisar de conta. */
    fun testar(ctx: Context): String? {
        if (!Sessao.configurada(ctx)) return "Servidor não configurado"
        val r = Http.enviar("GET", base(ctx) + "/auth/v1/settings", cab(ctx))
        return when {
            r.ok -> null
            r.codigo == 0 -> r.mensagem()
            r.codigo == 401 || r.codigo == 403 -> "O servidor respondeu, mas recusou a chave. Copie de novo a chave publishable (ou anon)."
            r.codigo == 404 -> "Endereço errado: o servidor respondeu, mas não é o Supabase. Use o Project URL."
            else -> "Servidor respondeu com erro ${r.codigo}: ${r.mensagem()}"
        }
    }

    // ---------- Autenticação ----------
    private fun guardarSessao(ctx: Context, j: JSONObject) {
        val user = j.optJSONObject("user") ?: JSONObject()
        Sessao.editar(ctx) {
            putString("access", j.optString("access_token"))
            putString("refresh", j.optString("refresh_token"))
            putLong("expira", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
            if (user.has("id")) putString("uid", user.getString("id"))
            if (user.has("email")) putString("email", user.getString("email"))
        }
    }

    /** Cria conta ou entra. Devolve null se deu certo, ou a mensagem de erro. */
    fun entrar(ctx: Context, email: String, senha: String, criar: Boolean, nome: String): String? {
        if (!Sessao.configurada(ctx)) return "Configure o servidor primeiro"
        val corpo = JSONObject().put("email", email.trim()).put("password", senha)
        val r = if (criar) {
            corpo.put("data", JSONObject().put("nome", nome))
            Http.enviar("POST", base(ctx) + "/auth/v1/signup", cab(ctx), corpo.toString())
        } else Http.enviar("POST", base(ctx) + "/auth/v1/token?grant_type=password", cab(ctx), corpo.toString())
        if (!r.ok) return r.mensagem()
        val j = r.json()
        if (!j.has("access_token")) return "Conta criada. Confirme o e-mail que o Supabase enviou e depois toque em Entrar."
        return aposLogin(ctx, j, nome)
    }

    /** Depois de qualquer login: guarda a sessão, assume os dados deste aparelho e sincroniza. */
    private fun aposLogin(ctx: Context, j: JSONObject, nome: String): String? {
        val anterior = Sessao.eu(ctx)
        guardarSessao(ctx, j)
        val uid = Sessao.uid(ctx) ?: return "Resposta sem usuário"
        val nomeConta = j.optJSONObject("user")?.optJSONObject("user_metadata")?.optString("nome").orEmpty()
        when {
            nome.isNotBlank() -> Ajustes.salvarNome(ctx, nome.trim())
            nomeConta.isNotBlank() -> Ajustes.salvarNome(ctx, nomeConta)
        }
        val banco = Banco.de(ctx)
        // Aparelho novo sem lançamentos: descarta o que foi criado automaticamente, para não duplicar
        if (anterior == "local" && !banco.existeTipoDoDono("lanc", "local")) banco.apagarTudoDoDono("local")
        if (anterior != uid) banco.trocarDono(anterior, uid)
        Sessao.editar(ctx) { remove("ultima"); remove("erro") }
        sincronizarAgora(ctx)
        return null
    }

    // ---------- Senha ----------
    /** Envia o e-mail de recuperação com o código. */
    fun recuperarSenha(ctx: Context, email: String): String? {
        if (!email.contains("@")) return "Escreva o e-mail da conta"
        val r = Http.enviar("POST", base(ctx) + "/auth/v1/recover", cab(ctx), JSONObject().put("email", email.trim()).toString())
        return if (r.ok) null else r.mensagem()
    }

    /** Confirma o código recebido por e-mail, define a nova senha e entra. */
    fun redefinirSenha(ctx: Context, email: String, codigo: String, nova: String): String? {
        if (nova.length < 6) return "A senha nova precisa de pelo menos 6 caracteres"
        val r = Http.enviar("POST", base(ctx) + "/auth/v1/verify", cab(ctx),
            JSONObject().put("type", "recovery").put("email", email.trim()).put("token", codigo.filter { it.isDigit() }).toString())
        if (!r.ok) return if (r.codigo in 400..499) "Código inválido ou expirado. Peça outro." else r.mensagem()
        val j = r.json()
        if (!j.has("access_token")) return "O servidor não devolveu a sessão. Peça outro código."
        guardarSessao(ctx, j)
        trocarSenha(ctx, nova)?.let { return it }
        return aposLogin(ctx, j, "")
    }

    /** Troca a senha de quem já está conectado. */
    fun trocarSenha(ctx: Context, nova: String): String? {
        if (nova.length < 6) return "A senha nova precisa de pelo menos 6 caracteres"
        val t = token(ctx) ?: return "Entre na conta primeiro"
        val r = Http.enviar("PUT", base(ctx) + "/auth/v1/user", cab(ctx, t), JSONObject().put("password", nova).toString())
        return when {
            r.ok -> null
            r.mensagem().contains("different from the old", true) -> "A senha nova tem de ser diferente da atual"
            else -> r.mensagem()
        }
    }

    fun sair(ctx: Context) {
        Sessao.sair(ctx)
        Banco.de(ctx).apagarTudo()   // os dados continuam no servidor; ao entrar de novo, voltam
        avisarTelas()
    }

    private fun token(ctx: Context): String? {
        if (!Sessao.logado(ctx)) return null
        if (System.currentTimeMillis() < Sessao.expira(ctx) - 60_000 && Sessao.access(ctx).isNotEmpty()) return Sessao.access(ctx)
        val r = Http.enviar("POST", base(ctx) + "/auth/v1/token?grant_type=refresh_token", cab(ctx),
            JSONObject().put("refresh_token", Sessao.refresh(ctx)).toString())
        if (!r.ok) {
            if (r.codigo in 400..499) Sessao.editar(ctx) { putString("erro", "Sessão expirada. Entre de novo.") }
            return null
        }
        guardarSessao(ctx, r.json())
        return Sessao.access(ctx)
    }

    private fun rpc(ctx: Context, funcao: String, args: JSONObject): Resp {
        val t = token(ctx) ?: return Resp(401, """{"message":"Faça login"}""")
        return Http.enviar("POST", base(ctx) + "/rest/v1/rpc/$funcao", cab(ctx, t), args.toString())
    }

    // ---------- Casal ----------
    /** Cria (ou recupera) o código do casal e devolve o texto do convite. */
    fun convidar(ctx: Context): Pair<String?, String?> {
        val r = rpc(ctx, "criar_casal", JSONObject().put("p_nome", Ajustes.nome(ctx)))
        if (!r.ok) return null to r.mensagem()
        val codigo = r.corpo.trim().trim('"')
        Sessao.editar(ctx) { putString("codigo", codigo) }
        atualizarCasal(ctx)
        return textoConvite(ctx, codigo) to null
    }

    fun entrarNoCasal(ctx: Context, codigo: String, sincronizarDepois: Boolean = true): String? {
        val r = rpc(ctx, "entrar_casal", JSONObject().put("p_codigo", codigo).put("p_nome", Ajustes.nome(ctx)))
        if (!r.ok) return r.mensagem()   // falha de rede: o convite fica pendente e tenta de novo
        val resultado = r.corpo.trim().trim('"')
        Sessao.editar(ctx) { remove("convitePendente"); if (resultado == "ok") remove("ultima") }
        val erro = when (resultado) {
            "ok" -> null
            "codigo_invalido" -> "Código do casal não encontrado"
            "casal_completo" -> "Este casal já tem duas pessoas"
            "ja_em_casal" -> "Você já está noutro casal. Saia dele primeiro."
            else -> "Não foi possível entrar no casal"
        }
        if (erro != null) Sessao.editar(ctx) { putString("erro", erro) }
        else if (sincronizarDepois) sincronizarAgora(ctx)
        return erro
    }

    fun sairDoCasal(ctx: Context): String? {
        val r = rpc(ctx, "sair_casal", JSONObject())
        if (!r.ok) return r.mensagem()
        Sessao.editar(ctx) { remove("casal"); remove("codigo"); remove("parceiroId"); remove("parceiroNome") }
        limparDoParceiro(ctx)
        sincronizarAgora(ctx)
        return null
    }

    fun definirNome(ctx: Context, nome: String) {
        if (!Sessao.logado(ctx)) return
        fila.execute { rpc(ctx, "definir_nome", JSONObject().put("p_nome", nome)) }
    }

    private fun limparDoParceiro(ctx: Context) {
        val me = Sessao.eu(ctx)
        val banco = Banco.de(ctx)
        banco.exportar().let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            .filter { it.getString("dono") != me }
            .forEach { banco.writableDatabase.delete("registros", "id = ?", arrayOf(it.getString("id"))) }
    }

    private fun atualizarCasal(ctx: Context) {
        val t = token(ctx) ?: return
        val r = Http.enviar("GET", base(ctx) + "/rest/v1/membros?select=casal_id,user_id,nome", cab(ctx, t))
        if (!r.ok) return
        val arr = try { JSONArray(r.corpo) } catch (e: Exception) { return }
        val me = Sessao.uid(ctx)
        var casal = ""; var pid = ""; var pnome = ""
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            casal = o.optString("casal_id")
            if (o.optString("user_id") != me) { pid = o.optString("user_id"); pnome = o.optString("nome") }
        }
        val tinhaParceiro = Sessao.parceiroId(ctx).isNotEmpty()
        Sessao.editar(ctx) { putString("casal", casal); putString("parceiroId", pid); putString("parceiroNome", pnome) }
        if (casal.isNotEmpty() && Sessao.codigo(ctx).isEmpty()) {
            val c = Http.enviar("GET", base(ctx) + "/rest/v1/casais?select=codigo", cab(ctx, t))
            if (c.ok) try { JSONArray(c.corpo).optJSONObject(0)?.optString("codigo")?.let { cod -> Sessao.editar(ctx) { putString("codigo", cod) } } } catch (e: Exception) {}
        }
        if (casal.isEmpty()) Sessao.editar(ctx) { remove("codigo") }
        if (tinhaParceiro && pid.isEmpty()) limparDoParceiro(ctx)
    }

    // ---------- Sincronização ----------
    /** Pede uma sincronização daqui a pouco (junta várias mudanças seguidas numa só ida ao servidor). */
    fun agendar(ctx: Context, atraso: Long = 1500) {
        if (!Sessao.logado(ctx)) return
        val app = ctx.applicationContext
        synchronized(this) {
            agendada?.cancel(false)
            agendada = fila.schedule({ sincronizarAgora(app) }, atraso, TimeUnit.MILLISECONDS)
        }
    }

    /** Sincroniza em segundo plano e chama de volta ao terminar. */
    fun sincronizar(ctx: Context, fim: ((String?) -> Unit)? = null) {
        val app = ctx.applicationContext
        fila.execute { val e = sincronizarAgora(app); fim?.invoke(e) }
    }

    fun executar(bloco: () -> Unit) = fila.execute(bloco)

    /** Envia o que mudou aqui, recebe o que mudou lá. Devolve null se deu certo. */
    @Synchronized
    fun sincronizarAgora(ctx: Context): String? {
        if (!Sessao.logado(ctx)) return "Sem conta"
        val t = token(ctx) ?: return if (Sessao.erro(ctx).startsWith("Sessão expirada")) Sessao.erro(ctx).also { avisarTelas() } else semRede(ctx)
        val banco = Banco.de(ctx)
        val me = Sessao.eu(ctx)

        Sessao.convitePendente(ctx).takeIf { it.isNotEmpty() }?.let { entrarNoCasal(ctx, it, false) }

        // 1. Enviar
        while (true) {
            val pend = banco.sujos(200)
            if (pend.isEmpty()) break
            val meus = pend.filter { it.reg.dono == me }
            pend.filter { it.reg.dono != me }.forEach { banco.limpar(it.reg.id, it.ver) }   // nunca envia o que é do outro
            if (meus.isEmpty()) continue
            val arr = JSONArray()
            meus.forEach {
                arr.put(JSONObject().put("id", it.reg.id).put("tipo", it.reg.tipo).put("compartilhado", it.reg.sh)
                    .put("dados", it.reg.dados).put("apagado", it.reg.apagado))
            }
            val cab = cab(ctx, t) + mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
            val r = Http.enviar("POST", base(ctx) + "/rest/v1/registros?on_conflict=id", cab, arr.toString())
            if (!r.ok) return if (r.codigo == 0) semRede(ctx) else erro(ctx, "Falha ao enviar: " + r.mensagem())
            meus.forEach { banco.limpar(it.reg.id, it.ver) }
        }

        atualizarCasal(ctx)

        // 2. Receber
        val primeira = Sessao.ultima(ctx).isEmpty()
        var desde = Sessao.ultima(ctx).ifEmpty { "1970-01-01T00:00:00+00:00" }
        val novidades = ArrayList<Reg>()
        while (true) {
            val url = base(ctx) + "/rest/v1/registros?select=id,dono,tipo,compartilhado,dados,apagado,atualizado" +
                "&atualizado=gt." + URLEncoder.encode(desde, "UTF-8") + "&order=atualizado.asc&limit=1000"
            val r = Http.enviar("GET", url, cab(ctx, t))
            if (!r.ok) return if (r.codigo == 0) semRede(ctx) else erro(ctx, "Falha ao receber: " + r.mensagem())
            val arr = try { JSONArray(r.corpo) } catch (e: Exception) { return erro(ctx, "Resposta inválida do servidor") }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val reg = Reg(o.getString("id"), o.getString("tipo"), o.getString("dono"), o.optBoolean("compartilhado"),
                    o.optJSONObject("dados") ?: JSONObject(), o.optBoolean("apagado"))
                val at = o.getString("atualizado")
                val jaTinha = banco.um(reg.id) != null
                banco.aplicarRemoto(reg, at)
                if (!primeira && !jaTinha && reg.dono != me && !reg.apagado) novidades.add(reg)
                desde = at
            }
            Sessao.editar(ctx) { putString("ultima", desde) }
            if (arr.length() < 1000) break
        }
        Sessao.editar(ctx) { putLong("ultimaMs", System.currentTimeMillis()); if (!Sessao.erro(ctx).startsWith("Código") && !Sessao.erro(ctx).startsWith("Este casal") && !Sessao.erro(ctx).startsWith("Você já")) remove("erro") }
        if (novidades.isNotEmpty()) avisarNovidades(ctx, novidades)
        if (Ajustes.notifAtiva(ctx) || Ajustes.agitarAtivo(ctx)) Notificacao.mostrar(ctx)
        avisarTelas()
        return null
    }

    /** Sem internet no momento: guarda tudo e tenta de novo sozinho daqui a 1 minuto. */
    private fun semRede(ctx: Context): String {
        agendar(ctx, 60_000)
        return erro(ctx, "Sem internet agora. O que você lançou fica guardado e é enviado sozinho quando a ligação voltar.")
    }

    private fun erro(ctx: Context, msg: String): String {
        Sessao.editar(ctx) { putString("erro", msg) }
        avisarTelas()
        return msg
    }

    // ---------- Avisos do parceiro ----------
    private const val CANAL = "casal"

    private fun avisarNovidades(ctx: Context, regs: List<Reg>) {
        if (telaVisivel) return
        val quem = Sessao.parceiroNome(ctx).ifEmpty { "Seu parceiro(a)" }
        val me = Sessao.eu(ctx)
        val banco = Banco.de(ctx)
        val linhas = regs.mapNotNull { r ->
            val alvo = r.dados.optString("alvo").takeIf { it.isNotEmpty() }?.let { banco.um(it) }
            val sobre = alvo?.dados?.let { " (${Formato.moeda(it.optDouble("v"))} ${it.optString("d")})" } ?: ""
            when (r.tipo) {
                "coment" -> "$quem comentou$sobre: ${r.dados.optString("t")}"
                "curtida" -> if (alvo?.dono == me) "$quem curtiu$sobre" else null
                "lanc" -> if (r.dados.optString("cf").isEmpty()) {
                    val sinal = if (r.dados.optString("t") == "r") "+" else ""
                    "$quem lançou $sinal${Formato.moeda(r.dados.optDouble("v"))} ${r.dados.optString("d")}"
                } else null
                "pago" -> "$quem marcou uma conta como paga"
                "meta" -> "$quem criou o destino ${r.dados.optString("n")}"
                else -> null
            }
        }
        if (linhas.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Atividade do casal", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 40, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val texto = linhas.take(6).joinToString("\n")
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle(if (linhas.size == 1) quem else "$quem: ${linhas.size} novidades")
            .setContentText(linhas.first())
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(4, n)
    }
}
