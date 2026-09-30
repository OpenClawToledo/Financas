package com.pessoal.financas

import java.text.Normalizer

/**
 * Categorias em dois níveis: "Casa" ou "Casa › Luz". A árvore, os emojis, as cores e as palavras que detetam
 * cada categoria vêm de assets/categorias.json — o mesmo arquivo que o iPhone usa.
 * Categorias antigas ("Mercado", "Moradia", "Contas fixas"…) são convertidas na leitura; nada é reescrito.
 */
object Categorias {
    const val SEP = " › "
    const val OUTROS = "Outros"
    const val RECEITAS = "Receitas"
    const val OUTRAS_RECEITAS = "Receitas › Outras Receitas"

    data class Sub(val n: String, val e: String)
    data class Cat(val n: String, val e: String, val cor: String, val subs: List<Sub>, val receita: Boolean)

    var arvore: List<Cat> = emptyList(); private set
    private var regras: List<Pair<String, List<String>>> = emptyList()
    private var regrasReceita: List<Pair<String, List<String>>> = emptyList()
    private var legado: Map<String, String> = emptyMap()
    private var apelidos: Map<String, String> = emptyMap()

    /** Categorias criadas pela pessoa (caminhos completos); o app liga isto ao Armazem. */
    @Volatile var fonteProprias: () -> List<String> = { emptyList() }
    val proprias: List<String> get() = try { fonteProprias() } catch (e: Exception) { emptyList() }

    val todas: List<String> get() = arvore.filter { !it.receita }.flatMap { c -> listOf(c.n) + c.subs.map { c.n + SEP + it.n } }
        .plus(proprias.filter { !it.startsWith(RECEITAS + SEP) && it != RECEITAS })
    val receitas: List<String> get() = arvore.filter { it.receita }.flatMap { c -> c.subs.map { c.n + SEP + it.n } }
        .plus(proprias.filter { it.startsWith(RECEITAS + SEP) })

    @Suppress("UNCHECKED_CAST")
    fun carregar(json: String) {
        val o = Json.ler(json) as Map<String, Any?>
        arvore = (o["arvore"] as List<Map<String, Any?>>).map { c ->
            Cat(c["n"] as String, c["e"] as String, c["cor"] as String,
                (c["subs"] as List<Map<String, Any?>>).map { Sub(it["n"] as String, it["e"] as String) }, c["receita"] == true)
        }
        fun lista(k: String) = (o[k] as List<List<Any?>>).map { it[0] as String to (it[1] as List<String>) }
        regras = lista("regras")
        regrasReceita = lista("regrasReceita")
        legado = (o["legado"] as Map<String, String>)
        apelidos = (o["apelidos"] as Map<String, String>)
    }

    fun normalizar(s: String): String =
        Normalizer.normalize(" " + s.lowercase() + " ", Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Categoria antiga ou desconhecida → caminho na árvore nova. */
    fun canon(c: String): String {
        if (c.isBlank()) return c
        legado[c]?.let { return it }
        return c
    }

    // Palavras curtas (até 4 letras) só valem como palavra inteira; as outras, no começo de uma palavra
    private val cacheRegex = HashMap<String, Regex>()
    private fun casa(texto: String, chave: String): Boolean {
        val re = synchronized(cacheRegex) {
            cacheRegex.getOrPut(chave) {
                val k = chave.trim()
                val fim = if (k.length <= 4) "(?![a-z0-9])" else ""
                Regex("(?<![a-z0-9])" + Regex.escape(k) + fim)
            }
        }
        return re.containsMatchIn(texto)
    }

    fun detectar(descricao: String, receita: Boolean = false): String {
        val d = normalizar(descricao)
        val lista = if (receita) regrasReceita else regras
        for ((cat, palavras) in lista) if (palavras.any { casa(d, it) }) return cat
        return if (receita) OUTRAS_RECEITAS else OUTROS
    }

    /** Nomes que se podem escrever no fim do texto ("12 corte de cabelo salão de beleza"). */
    private fun nomes(receita: Boolean): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val validas = if (receita) receitas else todas
        validas.forEach { caminho ->
            val partes = caminho.split(SEP)
            m.putIfAbsent(normalizar(partes.last()).trim(), caminho)
        }
        // o nome do pai ganha do da sub quando há dois iguais ("Outros")
        validas.filter { !it.contains(SEP) }.forEach { m[normalizar(it).trim()] = it }
        apelidos.forEach { (k, v) -> if (v in validas) m.putIfAbsent(k, v) }
        return m
    }

    /**
     * Categoria escrita pela pessoa: "#saude" em qualquer lugar ou o nome no fim ("12 corte de cabelo saúde").
     * Devolve a categoria e a descrição sem o nome, ou null.
     */
    fun escrita(descricao: String, receita: Boolean): Pair<String, String>? {
        val mapa = nomes(receita)
        val palavras = descricao.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun achar(trecho: String): String? = mapa[normalizar(trecho.removePrefix("#")).trim()]
        palavras.forEachIndexed { i, w ->
            if (w.startsWith("#") && w.length > 1) achar(w)?.let { c -> return c to palavras.filterIndexed { k, _ -> k != i }.joinToString(" ") }
        }
        for (n in 3 downTo 1) {
            if (palavras.size <= n) continue   // precisa sobrar descrição
            achar(palavras.takeLast(n).joinToString(" "))?.let { return it to palavras.dropLast(n).joinToString(" ") }
        }
        return null
    }
}

/** Leitor de JSON mínimo (sem dependências), para o arquivo de categorias. */
object Json {
    fun ler(s: String): Any? { val p = Leitor(s); val v = p.valor(); return v }

    private class Leitor(val s: String) {
        var i = 0
        fun esp() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun valor(): Any? {
            esp()
            return when (val c = s[i]) {
                '{' -> objeto()
                '[' -> lista()
                '"' -> texto()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) numero() else error("JSON inválido em $i")
            }
        }
        fun objeto(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; esp()
            if (s[i] == '}') { i++; return m }
            while (true) {
                esp(); val k = texto(); esp(); i++ /* : */
                m[k] = valor(); esp()
                if (s[i] == ',') { i++; continue }
                i++; return m
            }
        }
        fun lista(): List<Any?> {
            val l = ArrayList<Any?>(); i++; esp()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(valor()); esp()
                if (s[i] == ',') { i++; continue }
                i++; return l
            }
        }
        fun texto(): String {
            val b = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> b.append(s[i])
                    }
                } else b.append(s[i])
                i++
            }
            i++; return b.toString()
        }
        fun numero(): Double {
            val ini = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(ini, i).toDouble()
        }
    }
}
