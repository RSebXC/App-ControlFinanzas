package gt.finanzas.privadas

import java.math.BigDecimal
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

/** Reglas privadas y deterministas. Nunca procesa credenciales, códigos ni alertas de inicio de sesión. */
object BankParser {
    data class Parsed(
        val amountCents: Long,
        val accountLast4: String,
        val accountType: String,
        val merchant: String,
        val kind: String,
        val category: String,
        val note: String = "",
        val bank: String = "Banrural",
        val embeddedTime: Long? = null
    )

    private val money = Regex("(?i)\\bQ\\s*\\.?\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2})?)")
    // Ejemplos ficticios: Cuenta de Ahorro ****2222, Cuenta Monetaria *** ****1111.
    private val account = Regex("(?i)\\bcuenta\\s+(?:de\\s+)?(monetaria|ahorro)\\s+\\*+(?:\\s*\\*+)*\\s*(\\d{4})\\b")
    private val dateInText = Regex("(?i)\\b(?:el\\s+)?(\\d{2}/\\d{2}/\\d{4})\\s+a\\s+las\\s+(\\d{1,2}:\\d{2})\\b")
    private val unsafe = Regex("(?i)(?:\\botp\\b|\\bpin\\b|c[oó]digo\\s+(?:de\\s+)?(?:verificaci[oó]n|confirmaci[oó]n|para)|contrase[nñ]a|clave\\s+temporal|se\\s+ha\\s+iniciado\\s+sesi[oó]n|tu\\s+usuario\\s*:|accesos\\s+a\\s+los\\s+canales)")
    private val biAccount = Regex("(?i)\\bcuenta\\s+([A-Z][A-Z0-9]{2,20})\\b")

    fun parse(raw: String): Parsed? {
        val line = raw.trim().replace(Regex("\\s+"), " ")
        if (line.isBlank() || unsafe.containsMatchIn(line)) return null
        val normalized = fold(line)
        return when {
            normalized.startsWith("bimovil:") -> parseBi(line, normalized)
            normalized.contains("banrural") || normalized.startsWith("se debito de tu cuenta") ||
                normalized.startsWith("se acredito a tu cuenta") ||
                normalized.startsWith("se realizo una compra en pos") ||
                normalized.startsWith("recibiste q") -> parseBanrural(line, normalized)
            else -> null
        }
    }

    private fun parseBanrural(line: String, normalized: String): Parsed? {
        if (!listOf("se debito", "se acredito", "se devolvio", "se realizo una compra", "recibiste q").any { normalized.contains(it) }) return null
        val isReceived = normalized.contains("recibiste q")
        val a = account.find(line) ?: return null
        val cents = amount(line) ?: return null
        if (cents <= 0) return null // Las autorizaciones Q0 no son gastos reales.
        val after = line.substring(a.range.last + 1).trim()
        // Formato antiguo: "por consumo en: XYZ". Formato nuevo: "en XYZ, el dd/MM/yyyy a las HH:mm".
        val detail = (if (isReceived) {
            // Formato nuevo: "Recibiste Q<importe> ... cuenta Monetaria ****1111 de UNIDAD APP ..."
            Regex("(?i)^de\\s+(.+)$").find(after)?.groupValues?.get(1)?.trim()
        } else null) ?: Regex("(?i)\\b(?:por\\s+consumo\\s+)?en\\s*:?\\s*(.+)$")
            .find(after)?.groupValues?.get(1)?.trim() ?: "Operación bancaria sin descripción"
        val cleaned = detail.replace(Regex("(?i),?\\s*el\\s+\\d{2}/\\d{2}/\\d{4}\\s+a\\s+las\\s+\\d{1,2}:\\d{2}\\s*\\.?$"), "")
            .trim().trimEnd('.', ',')
        val kind = when {
            normalized.contains("se devolvio") -> "REFUND"
            normalized.contains("se acredito") || isReceived -> "PENDING_CREDIT"
            normalized.contains("se realizo una compra") -> "EXPENSE"
            normalized.contains("en cajero automatico") -> "PENDING_DEBIT"
            normalized.contains("en pos ") || normalized.contains("en googlepay ") || normalized.contains("en applepay ") || normalized.contains("por consumo en") -> "EXPENSE"
            else -> "PENDING_DEBIT" // Un débito APP/TIF/recaudo no implica consumo.
        }
        val t = dateInText.find(line)?.let { match ->
            try { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { isLenient = false }
                .parse(match.groupValues[1] + " " + match.groupValues[2])?.time }
            catch (_: Exception) { null }
        }
        val type = if (a.groupValues[1].equals("ahorro", true)) "Ahorro" else "Monetaria"
        return Parsed(cents, a.groupValues[2], type, cleaned, kind, categoryFor(cleaned), bank="Banrural", embeddedTime=t)
    }

    private fun parseBi(line: String, normalized: String): Parsed? {
        if (!normalized.contains("credito por") && !normalized.contains("consumo por") && !normalized.contains("debito por")) return null
        val a = biAccount.find(line) ?: return null
        val cents = amount(line) ?: return null
        if(cents <= 0) return null
        val key = a.groupValues[1].uppercase(Locale.ROOT)
        val kind = if (normalized.contains("credito por")) "PENDING_CREDIT" else if (normalized.contains("consumo por")) "EXPENSE" else "PENDING_DEBIT"
        val desc = if (kind == "EXPENSE") {
            val capture = Regex("(?i)\\bconsumo\\s+por\\s+Q\\s*\\.?[\\d,.]+\\s+en\\s+(.+?)\\s+cuenta\\s+[A-Z0-9]+").find(line)
            capture?.groupValues?.get(1)?.trim() ?: "Consumo BI"
        } else {
            val capture = Regex("(?i)\\ben\\s+la\\s+agencia\\s+(.+?)\\s+\\d{1,2}-[A-Za-z]{3}").find(line)
            capture?.groupValues?.get(1)?.trim()?.let { "Agencia $it" } ?: "Movimiento BI"
        }
        // BI envía identificadores de cuenta (MONE/BICHEQUE1), no los últimos cuatro dígitos.
        // Se conservan como etiquetas y nunca se inventan números bancarios.
        return Parsed(cents,key, "Cuenta",desc,kind,categoryFor(desc),bank="Banco Industrial")
    }

    private fun amount(line: String): Long? {
        val value = money.find(line)?.groupValues?.get(1)?.replace(",", "") ?: return null
        return try { BigDecimal(value).movePointRight(2).longValueExact() } catch (_: Exception) { null }
    }

    fun categoryFor(merchant: String): String {
        val s = fold(merchant)
        return when {
            listOf("gasolina", "shell", "puma ", "texaco", "uno gas", "gasolinera", "est de serv", "est. de serv", "estacion de servicio", "gasolin").any { s.contains(it) } -> "Gasolina"
            listOf("restaurante", "taco", "burger", "pizza", "mcdonald", "kfc", "domino", "cafe", "comida", "sarita", "qdely", "anfora", "restobar").any { s.contains(it) } -> "Comida"
            listOf("parqueo", "parking", "uber rides", "indriver", "viaje transporte", "transmetro").any { s.contains(it) } -> "Transporte"
            listOf("claro", "tigo", "internet", "energia", "eegsa", "agua municipal").any { s.contains(it) } -> "Servicios"
            listOf("chatgpt", "crunchyroll", "netflix", "spotify", "youtube premium", "disney plus").any { s.contains(it) } -> "Suscripciones"
            listOf("cinestar", "cinepolis", "cine ", "cinemark", "steamgames", "steam purchase", "teatro").any { s.contains(it) } -> "Entretenimiento"
            listOf("dollarcity", "supermercado", "walmart", "paiz", "despensa", "pricesmart", "cemaco", "kemik", "novex", "temu", "adoc").any { s.contains(it) } -> "Compras"
            listOf("hospital", "farmacia", "medico").any { s.contains(it) } -> "Salud"
            listOf("universidad", "usac", "colegio", "libreria").any { s.contains(it) } -> "Educación"
            listOf("zoologico", "zoo la aurora").any { s.contains(it) } -> "Entretenimiento"
            // Comerciales, cajeros, transferencias, débitos bancarios y comercios desconocidos quedan pendientes.
            else -> "Por revisar"
        }
    }

    fun fold(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
}
