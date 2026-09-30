package com.pessoal.financas

import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * O que se consegue tirar de uma fatura (PDF, foto ou texto de email).
 * pm = sugestão de repetição em meses (0 = não sabe; 1 = mensal, 2, 3, 6, 12).
 * parcela/totalParcelas = "prestação 12 de 48" (crédito, seguro fracionado).
 */
data class Fatura(
    val emissor: String,
    val valor: Double?,
    val dataLimite: LocalDate?,
    val dataEmissao: LocalDate?,
    val entidade: String,
    val referencia: String,
    val periodoIni: LocalDate?,
    val periodoFim: LocalDate?,
    val pm: Int,
    val parcela: Int,
    val totalParcelas: Int,
    val debitoDireto: Boolean,
    val categoria: String,
    val variavel: Boolean,
    val nif: String,
    val origemValor: String
)

/** Lê faturas portuguesas (e brasileiras simples) sem servidor: só texto e o código QR da fatura. */
object LeitorFatura {
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    // Empresas conhecidas: nome a mostrar, categoria e se o valor costuma variar
    private data class Marca(val chaves: List<String>, val nome: String, val cat: String, val variavel: Boolean, val pm: Int)
    private val marcas = listOf(
        Marca(listOf("edp comercial", "edp "), "EDP", "Casa › Luz", true, 1),
        Marca(listOf("galp"), "Galp", "Casa › Luz", true, 1),
        Marca(listOf("goldenergy"), "Goldenergy", "Casa › Luz", true, 1),
        Marca(listOf("endesa"), "Endesa", "Casa › Luz", true, 1),
        Marca(listOf("iberdrola"), "Iberdrola", "Casa › Luz", true, 1),
        Marca(listOf("repsol"), "Repsol", "Casa › Luz", true, 1),
        Marca(listOf("su eletricidade", "su electricidade"), "SU Eletricidade", "Casa › Luz", true, 1),
        Marca(listOf("meo", "altice"), "MEO", "Casa › Internet", false, 1),
        Marca(listOf("nos comunicacoes", "nos.pt", "nos "), "NOS", "Casa › Internet", false, 1),
        Marca(listOf("vodafone"), "Vodafone", "Casa › Internet", false, 1),
        Marca(listOf("nowo"), "NOWO", "Casa › Internet", false, 1),
        Marca(listOf("digi portugal", "digi "), "DIGI", "Casa › Internet", false, 1),
        Marca(listOf("epal"), "EPAL", "Casa › Água", true, 1),
        Marca(listOf("smas", "aguas de ", "aguas do ", "indaqua", "servicos municipalizados"), "Água", "Casa › Água", true, 1),
        Marca(listOf("fidelidade"), "Fidelidade", "", false, 0),
        Marca(listOf("tranquilidade"), "Tranquilidade", "", false, 0),
        Marca(listOf("generali"), "Generali", "", false, 0),
        Marca(listOf("allianz"), "Allianz", "", false, 0),
        Marca(listOf("ageas"), "Ageas", "", false, 0),
        Marca(listOf("liberty seguros"), "Liberty Seguros", "", false, 0),
        Marca(listOf("zurich"), "Zurich", "", false, 0),
        Marca(listOf("ok! teleseguros", "ok teleseguros"), "OK! Teleseguros", "", false, 0),
        Marca(listOf("via verde"), "Via Verde", "Transporte › Pedágio", true, 1),
        Marca(listOf("netflix"), "Netflix", "Casa › TV por Assinatura", false, 1),
        Marca(listOf("spotify"), "Spotify", "Lazer - Passeios", false, 1),
        Marca(listOf("fitness hut"), "Fitness Hut", "Gastos Pessoais › Academia", false, 1),
        Marca(listOf("solinca"), "Solinca", "Gastos Pessoais › Academia", false, 1),
        Marca(listOf("holmes place"), "Holmes Place", "Gastos Pessoais › Academia", false, 1),
        Marca(listOf("cofidis"), "Cofidis", "Dívidas", false, 1),
        Marca(listOf("cetelem"), "Cetelem", "Dívidas", false, 1),
        Marca(listOf("unibanco"), "Unibanco", "Dívidas", false, 1)
    )

    private val meses = mapOf(
        "jan" to 1, "fev" to 2, "mar" to 3, "abr" to 4, "mai" to 5, "jun" to 6,
        "jul" to 7, "ago" to 8, "set" to 9, "out" to 10, "nov" to 11, "dez" to 12
    )

    // ---------- valores ----------
    private val dinheiro = Regex("""(?:€|eur|r\$)?\s*(-?\d{1,3}(?:[.\s]\d{3})*,\d{2}|-?\d+\.\d{2}|-?\d+,\d{2}|-?\d{1,3}(?:,\d{3})+\.\d{2})\s*(?:€|eur\b)?""", RegexOption.IGNORE_CASE)

    fun numero(s: String): Double? {
        var x = s.replace(" ", "").replace(" ", "")
        x = when {
            Regex("""^-?\d{1,3}(,\d{3})+\.\d{2}$""").matches(x) -> x.replace(",", "")
            x.contains(',') -> x.replace(".", "").replace(',', '.')
            else -> x
        }
        return x.toDoubleOrNull()
    }

    /** Primeiro valor em dinheiro depois do rótulo, na mesma linha ou nas duas seguintes. */
    private fun valorApos(linhas: List<String>, i: Int, col: Int): Double? {
        for (k in 0..2) {
            val l = linhas.getOrNull(i + k) ?: break
            val trecho = if (k == 0) l.substring(minOf(col, l.length)) else l
            // ignora datas e percentagens
            val limpo = trecho.replace(Regex("""\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}"""), " ").replace(Regex("""\d+(?:[.,]\d+)?\s*%"""), " ")
            dinheiro.findAll(limpo).mapNotNull { numero(it.groupValues[1]) }.firstOrNull { it > 0 }?.let { return it }
        }
        return null
    }

    // ---------- datas ----------
    private val dataNum = Regex("""\b(\d{1,2})\s*[/.-]\s*(\d{1,2})\s*[/.-]\s*(\d{4}|\d{2})\b""")
    private val dataIso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val dataExtenso = Regex("""\b(\d{1,2})\s*(?:de\s+|-|\s)?(jan|fev|mar|abr|mai|jun|jul|ago|set|out|nov|dez)[a-zç]*\.?\s*(?:de\s+|-|\s)?(\d{4})\b""")

    private fun montar(a: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(if (a < 100) 2000 + a else a, m, d) } catch (e: Exception) { null }

    /** Todas as datas de um trecho, na ordem em que aparecem. */
    fun datas(trecho: String): List<LocalDate> {
        val t = norm(trecho)
        val achadas = ArrayList<Pair<Int, LocalDate>>()
        dataIso.findAll(t).forEach { m -> montar(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { achadas.add(m.range.first to it) } }
        dataNum.findAll(t).forEach { m ->
            if (achadas.any { it.first == m.range.first }) return@forEach
            montar(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { achadas.add(m.range.first to it) }
        }
        dataExtenso.findAll(t).forEach { m -> montar(m.groupValues[3].toInt(), meses[m.groupValues[2]] ?: 0, m.groupValues[1].toInt())?.let { achadas.add(m.range.first to it) } }
        return achadas.sortedBy { it.first }.map { it.second }
    }

    private fun dataApos(linhas: List<String>, i: Int, col: Int): LocalDate? {
        for (k in 0..2) {
            val l = linhas.getOrNull(i + k) ?: break
            datas(if (k == 0) l.substring(minOf(col, l.length)) else l).firstOrNull()?.let { return it }
        }
        return null
    }

    /** Procura o rótulo (já sem acentos) e devolve o que a função extrai logo depois. */
    private fun <T> porRotulo(linhas: List<String>, normais: List<String>, rotulos: List<String>, f: (List<String>, Int, Int) -> T?): Pair<T, String>? {
        for (r in rotulos) {
            // o rótulo tem de começar uma palavra ("total" não vale dentro de "subtotal")
            val re = Regex("(?<![a-z0-9])" + Regex.escape(r))
            for ((i, l) in normais.withIndex()) {
                val m = re.find(l) ?: continue
                f(linhas, i, m.range.last + 1)?.let { return it to r }
            }
        }
        return null
    }

    // ---------- QR da fatura (Portaria 195/2020): A:NIF*B:...*F:AAAAMMDD*...*O:total ----------
    fun lerQr(qr: String): Map<String, String>? {
        if (!qr.contains("A:") || !qr.contains("*")) return null
        val m = qr.split("*").mapNotNull { p -> p.indexOf(':').takeIf { it > 0 }?.let { p.substring(0, it).trim() to p.substring(it + 1).trim() } }.toMap()
        return if (m.containsKey("A") && (m.containsKey("O") || m.containsKey("F"))) m else null
    }

    // ---------- principal ----------
    fun analisar(texto: String, qrs: List<String> = emptyList()): Fatura {
        val linhas = texto.replace(" ", " ").replace("\r", "").split("\n").map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }
        val normais = linhas.map { norm(it) }
        val tudo = normais.joinToString("\n")
        val qr = qrs.firstNotNullOfOrNull { lerQr(it) }

        // valor: o que se paga (referência MB / "a pagar") vale mais que o total da fatura
        val rotulosPagar = listOf("montante a pagar", "total a pagar", "valor a pagar", "importancia a pagar", "valor a debitar", "montante a debitar",
            "valor da prestacao", "valor da mensalidade", "premio a pagar", "premio total", "montante")
        val rotulosTotal = listOf("total da fatura", "total fatura", "total do documento", "valor total", "total c/ iva", "total com iva", "total (eur)", "total eur", "total:", "total")
        var origem = ""
        var valor: Double? = porRotulo(linhas, normais, rotulosPagar) { l, i, c -> valorApos(l, i, c) }?.let { origem = it.second; it.first }
        if (valor == null) qr?.get("O")?.let { numero(it) }?.let { valor = it; origem = "qr" }
        if (valor == null) valor = porRotulo(linhas, normais, rotulosTotal) { l, i, c -> valorApos(l, i, c) }?.let { origem = it.second; it.first }

        // datas
        val rotulosLimite = listOf("data limite de pagamento", "data limite", "limite de pagamento", "pagar ate", "pagamento ate", "a pagar ate",
            "data de vencimento", "data vencimento", "vencimento", "data de debito", "data do debito", "data prevista de debito", "sera debitado em",
            "sera debitada em", "debito em", "cobranca em", "debitado a", "valido ate")
        val dataLimite = porRotulo(linhas, normais, rotulosLimite) { l, i, c -> dataApos(l, i, c) }?.first
        val rotulosEmissao = listOf("data de emissao", "data emissao", "emitida em", "data da fatura", "data do documento", "data de fatura", "data:")
        val dataEmissao = porRotulo(linhas, normais, rotulosEmissao) { l, i, c -> dataApos(l, i, c) }?.first
            ?: qr?.get("F")?.let { f -> if (f.length == 8) montar(f.substring(0, 4).toInt(), f.substring(4, 6).toInt(), f.substring(6, 8).toInt()) else null }

        // referência Multibanco
        val entidade = Regex("""entidade\s*:?\s*(\d{5})\b""").find(tudo)?.groupValues?.get(1).orEmpty()
        val referencia = Regex("""refer[e]ncia\s*(?:mb|multibanco)?\s*:?\s*((?:\d{3} ?){3})""").find(tudo)?.groupValues?.get(1)?.replace(" ", "")
            ?.chunked(3)?.joinToString(" ").orEmpty()

        // período faturado → quantos meses cobre
        var pIni: LocalDate? = null; var pFim: LocalDate? = null
        for ((i, l) in normais.withIndex()) {
            val c = Regex("""periodo(?: de faturacao| de consumo| faturado| de referencia)?""").find(l) ?: continue
            val ds = datas(linhas[i].substring(minOf(c.range.last + 1, linhas[i].length)) + " " + (linhas.getOrNull(i + 1) ?: ""))
            if (ds.size >= 2 && ds[1].isAfter(ds[0])) { pIni = ds[0]; pFim = ds[1]; break }
        }
        val mesesPeriodo = if (pIni != null && pFim != null) {
            val dias = ChronoUnit.DAYS.between(pIni, pFim) + 1
            listOf(1 to 31L, 2 to 62L, 3 to 93L, 6 to 185L, 12 to 370L).firstOrNull { dias <= it.second + 3 }?.first ?: 0
        } else 0

        // repetição dita por extenso (seguros: "fracionamento: trimestral")
        val palavraPm = listOf("mensal" to 1, "bimestral" to 2, "trimestral" to 3, "semestral" to 6, "anual" to 12)
        val pmTexto = Regex("""(?:fracionamento|periodicidade|pagamento|modalidade de pagamento|frequencia)\s*:?\s*(mensal|bimestral|trimestral|semestral|anual)""").find(tudo)
            ?.groupValues?.get(1)?.let { w -> palavraPm.first { it.first == w }.second } ?: 0

        // prestação 12 de 48 / 12/48 / 12.ª prestação de 48
        val prest = Regex("""prestacao\s*(?:n\.?\s*[oº]\s*)?(\d{1,3})\s*(?:/|de)\s*(\d{1,3})""").find(tudo)
            ?: Regex("""(\d{1,3})\s*\.?\s*[aª]?\s*prestacao\s*(?:de|/)\s*(\d{1,3})""").find(tudo)
        var parcela = prest?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var totalParcelas = prest?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (parcela <= 0 || totalParcelas < parcela) { parcela = 0; totalParcelas = 0 }

        val debito = Regex("""debito\s+dire(c)?to|sera debitad|cobranca por debito""").containsMatchIn(tudo)

        // emissor
        val corrido = " " + tudo.replace("\n", " ") + " "
        val marca = marcas.firstOrNull { m -> m.chaves.any { k -> Regex("(?<![a-z0-9])" + Regex.escape(k)).containsMatchIn(corrido) } }
        val emissor = marca?.nome ?: linhas.firstOrNull { Regex("""(?i)\b(s\.?a\.?|lda\.?|unipessoal)\b""").containsMatchIn(it) && it.length <= 60 }
            ?.replace(Regex("""(?i),?\s*\b(s\.?a\.?|lda\.?|unipessoal)\b.*$"""), "")?.trim()?.takeIf { it.length >= 2 }
            ?: "Fatura"

        val pm = when {
            pmTexto > 0 -> pmTexto
            totalParcelas > 0 -> 1
            mesesPeriodo > 0 -> mesesPeriodo
            else -> marca?.pm ?: 0
        }
        // seguradoras: o tipo de seguro decide a categoria
        val seguro = when {
            Regex("""automovel|viatura|seguro auto|matricula""").containsMatchIn(tudo) -> "Transporte › Seguro"
            Regex("""saude""").containsMatchIn(tudo) -> "Saúde › Plano de Saúde"
            else -> "Casa"
        }
        val categoria = marca?.cat?.ifEmpty { seguro } ?: Categorias.detectar(normais.take(40).joinToString(" "), false)

        return Fatura(emissor, valor, dataLimite, dataEmissao, entidade, referencia, pIni, pFim, pm, parcela, totalParcelas,
            debito, categoria, marca?.variavel ?: false, qr?.get("A").orEmpty(), origem)
    }
}
