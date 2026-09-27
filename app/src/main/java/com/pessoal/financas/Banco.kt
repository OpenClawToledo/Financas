package com.pessoal.financas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Tudo o que o app guarda é um "registro": um tipo, um dono, se é compartilhado com o casal e os dados em JSON.
 * Esse formato único é o que permite sincronizar com o servidor sem conhecer cada tela.
 *
 * Tipos: lanc, conta, pago, meta, aporte, ref, troca, curtida, coment.
 */
data class Reg(
    val id: String,
    val tipo: String,
    val dono: String,
    val sh: Boolean,
    val dados: JSONObject,
    val apagado: Boolean = false
)

class Banco private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "financas.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE registros (
                id TEXT PRIMARY KEY,
                tipo TEXT NOT NULL,
                dono TEXT NOT NULL,
                sh INTEGER NOT NULL DEFAULT 0,
                dados TEXT NOT NULL,
                apagado INTEGER NOT NULL DEFAULT 0,
                sujo INTEGER NOT NULL DEFAULT 1,
                ver INTEGER NOT NULL DEFAULT 0,
                at TEXT
            )"""
        )
        db.execSQL("CREATE INDEX idx_tipo ON registros(tipo, apagado)")
        db.execSQL("CREATE INDEX idx_sujo ON registros(sujo)")
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {}

    private fun ler(c: android.database.Cursor): Reg = Reg(
        c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1,
        try { JSONObject(c.getString(4)) } catch (e: Exception) { JSONObject() },
        c.getInt(5) == 1
    )

    private val colunas = arrayOf("id", "tipo", "dono", "sh", "dados", "apagado")

    fun todos(tipo: String): List<Reg> {
        val lista = ArrayList<Reg>()
        readableDatabase.query("registros", colunas, "tipo = ? AND apagado = 0", arrayOf(tipo), null, null, null).use { c ->
            while (c.moveToNext()) lista.add(ler(c))
        }
        return lista
    }

    fun todosComApagados(tipo: String): List<Reg> {
        val lista = ArrayList<Reg>()
        readableDatabase.query("registros", colunas, "tipo = ?", arrayOf(tipo), null, null, null).use { c ->
            while (c.moveToNext()) lista.add(ler(c))
        }
        return lista
    }

    fun um(id: String): Reg? =
        readableDatabase.query("registros", colunas, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) ler(c) else null
        }

    fun existeTipoDoDono(tipo: String, dono: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM registros WHERE tipo = ? AND dono = ? LIMIT 1", arrayOf(tipo, dono)).use { it.moveToFirst() }

    /** Grava uma mudança feita neste aparelho: fica marcada como "suja" até o servidor confirmar. */
    @Synchronized
    fun salvar(r: Reg) {
        val db = writableDatabase
        val verAtual = db.rawQuery("SELECT ver FROM registros WHERE id = ?", arrayOf(r.id)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val v = ContentValues().apply {
            put("id", r.id); put("tipo", r.tipo); put("dono", r.dono); put("sh", if (r.sh) 1 else 0)
            put("dados", r.dados.toString()); put("apagado", if (r.apagado) 1 else 0)
            put("sujo", 1); put("ver", verAtual + 1)
        }
        db.insertWithOnConflict("registros", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun apagar(id: String) { um(id)?.let { salvar(it.copy(apagado = true)) } }
    fun restaurar(id: String) { um(id)?.let { salvar(it.copy(apagado = false)) } }

    data class Pendente(val reg: Reg, val ver: Int)

    fun sujos(limite: Int = 200): List<Pendente> {
        val lista = ArrayList<Pendente>()
        readableDatabase.rawQuery(
            "SELECT id, tipo, dono, sh, dados, apagado, ver FROM registros WHERE sujo = 1 LIMIT $limite", null
        ).use { c -> while (c.moveToNext()) lista.add(Pendente(ler(c), c.getInt(6))) }
        return lista
    }

    fun contarSujos(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM registros WHERE sujo = 1", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Marca como enviado, desde que não tenha mudado de novo enquanto ia para o servidor. */
    @Synchronized
    fun limpar(id: String, ver: Int) {
        writableDatabase.execSQL("UPDATE registros SET sujo = 0 WHERE id = ? AND ver = ?", arrayOf<Any>(id, ver))
    }

    /** Aplica o que veio do servidor. Mudanças locais ainda não enviadas têm prioridade. */
    @Synchronized
    fun aplicarRemoto(r: Reg, at: String) {
        val db = writableDatabase
        val sujo = db.rawQuery("SELECT sujo FROM registros WHERE id = ?", arrayOf(r.id)).use { it.moveToFirst() && it.getInt(0) == 1 }
        if (sujo) return
        val v = ContentValues().apply {
            put("id", r.id); put("tipo", r.tipo); put("dono", r.dono); put("sh", if (r.sh) 1 else 0)
            put("dados", r.dados.toString()); put("apagado", if (r.apagado) 1 else 0)
            put("sujo", 0); put("ver", 0); put("at", at)
        }
        db.insertWithOnConflict("registros", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Ao entrar numa conta, o que foi feito antes sem conta passa a ser dessa pessoa. */
    @Synchronized
    fun trocarDono(de: String, para: String) {
        writableDatabase.execSQL("UPDATE registros SET dono = ?, sujo = 1, ver = ver + 1 WHERE dono = ?", arrayOf(para, de))
    }

    @Synchronized
    fun apagarTudoDoDono(dono: String) { writableDatabase.delete("registros", "dono = ?", arrayOf(dono)) }

    @Synchronized
    fun apagarTudo() { writableDatabase.delete("registros", null, null) }

    fun exportar(): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery("SELECT id, tipo, dono, sh, dados, apagado FROM registros", null).use { c ->
            while (c.moveToNext()) {
                val r = ler(c)
                arr.put(JSONObject().put("id", r.id).put("tipo", r.tipo).put("dono", r.dono).put("sh", r.sh).put("dados", r.dados).put("apagado", r.apagado))
            }
        }
        return arr
    }

    companion object {
        @Volatile private var inst: Banco? = null
        fun de(ctx: Context): Banco = inst ?: synchronized(this) { inst ?: Banco(ctx).also { inst = it } }
        fun novoId(): String = UUID.randomUUID().toString()
    }
}

/** Converte os dados da versão 0.5 (listas soltas) para registros. Roda uma única vez. */
object Migracao {
    fun executar(ctx: Context) {
        val p = ctx.getSharedPreferences("financas", Context.MODE_PRIVATE)
        if (p.getBoolean("migrado_v6", false)) return
        val b = Banco.de(ctx)
        val dono = "local"
        fun arr(k: String) = try { JSONArray(p.getString(k, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        fun objs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }

        val nome = p.getString("nome", "Eu") ?: "Eu"
        val contaIds = HashMap<Long, String>()
        objs(arr("contas")).forEach { o ->
            val id = Banco.novoId(); contaIds[o.getLong("id")] = id
            b.salvar(Reg(id, "conta", dono, false, JSONObject().put("n", o.getString("n")).put("v", o.getDouble("v"))
                .put("dia", o.getInt("dia")).put("e", o.optBoolean("e", false))))
        }
        val pagos = arr("pagos")
        for (i in 0 until pagos.length()) {
            val partes = pagos.getString(i).split(":")
            val cid = partes.getOrNull(1)?.toLongOrNull()?.let { contaIds[it] } ?: continue
            b.salvar(Reg(Banco.novoId(), "pago", dono, false, JSONObject().put("conta", cid).put("mes", partes[0])))
        }
        objs(arr("gastos")).forEach { o ->
            val id = Banco.novoId()
            val d = JSONObject().put("q", o.getLong("q")).put("v", o.getDouble("v")).put("d", o.optString("d"))
                .put("c", o.optString("c")).put("t", if (o.optString("t") == "r") "r" else "d")
            if (o.has("cf")) contaIds[o.optLong("cf")]?.let { d.put("cf", it) }
            b.salvar(Reg(id, "lanc", dono, false, d))
            if (o.optBoolean("l")) b.salvar(Reg(Banco.novoId(), "curtida", dono, false, JSONObject().put("alvo", id)))
            val cm = o.optJSONArray("cm")
            if (cm != null) for (k in 0 until cm.length()) {
                val c = cm.getJSONObject(k)
                b.salvar(Reg(Banco.novoId(), "coment", dono, false, JSONObject().put("alvo", id).put("t", c.optString("t"))
                    .put("a", c.optString("a", nome)).put("q", c.optLong("q"))))
            }
        }
        val metaIds = HashMap<Long, String>()
        objs(arr("metas")).forEach { o ->
            val id = Banco.novoId(); metaIds[o.getLong("id")] = id
            b.salvar(Reg(id, "meta", dono, false, JSONObject().put("n", o.getString("n")).put("alvo", o.getDouble("alvo"))
                .put("e", o.optString("e", "🎯")).put("tipo", o.optString("tipo", "j"))))
            val atual = o.optDouble("atual", 0.0)
            if (atual > 0) b.salvar(Reg(Banco.novoId(), "aporte", dono, false, JSONObject().put("meta", id).put("v", atual).put("q", System.currentTimeMillis())))
        }
        val refIds = HashMap<Long, String>()
        val refs = if (p.contains("referencias")) arr("referencias") else JSONArray()
        objs(refs).forEach { o ->
            val id = Banco.novoId(); refIds[o.getLong("id")] = id
            b.salvar(Reg(id, "ref", dono, false, JSONObject().put("n", o.getString("n")).put("e", o.optString("e"))
                .put("v", o.getDouble("v")).put("dest", o.optBoolean("dest")).put("evitar", o.optBoolean("evitar"))
                .put("m", metaIds[o.optLong("m")] ?: "")))
        }
        if (!p.contains("referencias")) {
            val cafe = p.getFloat("preco_cafe", 0f).toDouble()
            if (cafe > 0) b.salvar(Reg(Banco.novoId(), "ref", dono, false, JSONObject().put("n", "Café da manhã").put("e", "☕").put("v", cafe).put("dest", true).put("evitar", false).put("m", "")))
            val hora = p.getFloat("valor_hora", 0f).toDouble()
            if (hora > 0) b.salvar(Reg(Banco.novoId(), "ref", dono, false, JSONObject().put("n", "Hora de trabalho").put("e", "⏱").put("v", hora).put("dest", true).put("evitar", false).put("m", "")))
        }
        // As trocas antigas já estão somadas no aporte migrado acima, por isso não geram aporte novo
        objs(arr("trocas")).forEach { o ->
            b.salvar(Reg(Banco.novoId(), "troca", dono, false, JSONObject().put("q", o.getLong("q")).put("r", refIds[o.optLong("r")] ?: "")
                .put("v", o.getDouble("v")).put("n", o.optString("n")).put("e", o.optString("e"))
                .put("m", metaIds[o.optLong("m")] ?: "")))
        }
        p.edit().putBoolean("migrado_v6", true).commit()
    }
}

/** Backup em arquivo: tudo o que é desta pessoa, para restaurar noutro aparelho ou após reinstalar. */
object Backup {
    fun exportar(ctx: Context): String {
        val me = Sessao.eu(ctx)
        val regs = JSONArray()
        val todos = Banco.de(ctx).exportar()
        for (i in 0 until todos.length()) todos.getJSONObject(i).takeIf { it.getString("dono") == me }?.let { regs.put(it) }
        return JSONObject().put("app", "financas").put("versao", 3).put("data", System.currentTimeMillis())
            .put("nome", Ajustes.nome(ctx)).put("moeda", Ajustes.moeda(ctx)).put("registros", regs).toString(2)
    }

    fun importar(ctx: Context, texto: String): Boolean {
        val o = try { JSONObject(texto) } catch (e: Exception) { return false }
        if (o.optString("app") != "financas") return false
        val banco = Banco.de(ctx)
        val me = Sessao.eu(ctx)
        val regs = o.optJSONArray("registros")
        if (regs != null) {
            // formato atual: os registros viram desta pessoa (cópia de segurança pessoal)
            val donoOriginal = (0 until regs.length()).map { regs.getJSONObject(it).optString("dono") }.firstOrNull() ?: me
            banco.apagarTudoDoDono(me)
            for (i in 0 until regs.length()) {
                val r = regs.getJSONObject(i)
                if (r.optString("dono") != donoOriginal) continue
                banco.salvar(Reg(r.getString("id"), r.getString("tipo"), me, r.optBoolean("sh"),
                    r.optJSONObject("dados") ?: JSONObject(), r.optBoolean("apagado")))
            }
        } else {
            // backups antigos (v0.4 e v0.5): passam pela migração
            val p = ctx.getSharedPreferences("financas", Context.MODE_PRIVATE).edit()
            listOf("gastos", "contas", "pagos", "metas", "referencias", "trocas").forEach { k ->
                val a = o.optJSONArray(k); if (a != null) p.putString(k, a.toString()) else p.remove(k)
            }
            if (o.has("cafe")) p.putFloat("preco_cafe", o.optDouble("cafe").toFloat())
            if (o.has("hora")) p.putFloat("valor_hora", o.optDouble("hora").toFloat())
            p.putBoolean("migrado_v6", false).commit()
            banco.apagarTudoDoDono(me)
            banco.apagarTudoDoDono("local")
            Migracao.executar(ctx)
            if (me != "local") banco.trocarDono("local", me)
        }
        if (o.has("nome")) Ajustes.salvarNome(ctx, o.optString("nome"))
        if (o.has("moeda")) Ajustes.salvarMoeda(ctx, o.optString("moeda"))
        Nuvem.agendar(ctx, 0)
        return true
    }
}
