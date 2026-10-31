package com.ajit.dynamicisland.module.scanner

/**
 * What a scanned code means, so the island can offer the right button: open a link, join Wi-Fi, pay
 * with UPI, call, email, add a contact… Pure Kotlin, unit-tested with real-world code formats.
 */
sealed class Payload(val raw: String) {
    class Url(raw: String, val url: String, val host: String) : Payload(raw)
    class Wifi(raw: String, val ssid: String, val password: String?, val security: String, val hidden: Boolean) : Payload(raw)
    class Upi(raw: String, val vpa: String, val name: String?, val amount: String?, val note: String?) : Payload(raw)
    class Phone(raw: String, val number: String) : Payload(raw)
    class Email(raw: String, val to: String, val subject: String?, val body: String?) : Payload(raw)
    class Sms(raw: String, val number: String, val body: String?) : Payload(raw)
    class Geo(raw: String, val lat: Double, val lon: Double, val query: String?) : Payload(raw)
    class Contact(raw: String, val name: String?, val phone: String?, val email: String?, val org: String?) : Payload(raw)
    class Product(raw: String, val code: String) : Payload(raw)
    class Text(raw: String) : Payload(raw)

    /** Short heading for the island card and the history list. */
    val title: String get() = when (this) {
        is Url -> host.removePrefix("www.").ifBlank { "Link" }
        is Wifi -> "Wi-Fi: $ssid"
        is Upi -> "Pay ${name ?: vpa}" + (amount?.let { " · ₹$it" } ?: "")
        is Phone -> number
        is Email -> to
        is Sms -> "Message $number"
        is Geo -> query ?: "Location"
        is Contact -> name ?: phone ?: email ?: "Contact"
        is Product -> "Barcode $code"
        is Text -> raw.lineSequence().first().take(60)
    }

    /** One more line of detail (never the Wi-Fi password). */
    val detail: String get() = when (this) {
        is Url -> url.take(120)
        is Wifi -> (if (password != null) "$security network" else "Open network") + if (hidden) " · hidden" else ""
        is Upi -> "UPI ID $vpa" + (note?.let { " · $it" } ?: "")
        is Phone -> "Phone number"
        is Email -> subject ?: "Email address"
        is Sms -> body ?: "Text message"
        is Geo -> "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)
        is Contact -> listOfNotNull(phone, email, org).joinToString(" · ").ifBlank { "Contact card" }
        is Product -> "Product code"
        is Text -> if (raw.length > 60 || raw.contains('\n')) raw.take(140) else "Text"
    }

    val kind: String get() = this::class.simpleName ?: "Text"

    companion object {
        private val PRODUCT_FORMATS = setOf("EAN_13", "EAN_8", "UPC_A", "UPC_E")

        fun parse(text: String, format: String = "QR_CODE"): Payload {
            val t = text.trim()
            val lower = t.lowercase()
            return when {
                format in PRODUCT_FORMATS && t.all { it.isDigit() } -> Product(t, t)
                lower.startsWith("http://") || lower.startsWith("https://") -> url(t)
                lower.startsWith("www.") && !t.contains(' ') -> url("https://$t")
                lower.startsWith("wifi:") -> wifi(t) ?: Text(t)
                lower.startsWith("upi://pay") -> upi(t) ?: Text(t)
                lower.startsWith("tel:") -> Phone(t, t.substring(4).trim())
                lower.startsWith("mailto:") -> mailto(t)
                lower.startsWith("matmsg:") -> matmsg(t)
                lower.startsWith("smsto:") || lower.startsWith("sms:") -> sms(t)
                lower.startsWith("geo:") -> geo(t) ?: Text(t)
                lower.startsWith("begin:vcard") -> vcard(t)
                lower.startsWith("mecard:") -> mecard(t)
                else -> Text(t)
            }
        }

        private fun url(t: String): Payload {
            val host = runCatching { java.net.URI(t).host }.getOrNull()
            return if (host.isNullOrBlank()) Text(t) else Url(t, t, host.lowercase())
        }

        /** `WIFI:T:WPA;S:Home;P:pa\;ss;H:false;;` with backslash escapes (ZXing / Android format). */
        private fun wifi(t: String): Payload.Wifi? {
            val f = fields(t.substring(5))
            val ssid = f["S"]?.takeIf { it.isNotEmpty() } ?: return null
            val type = f["T"]?.uppercase().orEmpty()
            val pass = f["P"]?.takeIf { it.isNotEmpty() && type != "NOPASS" }
            val sec = when { pass == null -> "Open"; type.startsWith("WPA3") || type == "SAE" -> "WPA3"; type == "WEP" -> "WEP"; else -> "WPA" }
            return Wifi(t, ssid, pass, sec, f["H"].equals("true", ignoreCase = true))
        }

        /** `KEY:value;` pairs with `\;`, `\:`, `\,`, `\\` escapes. */
        fun fields(body: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val sb = StringBuilder(); var key: String? = null; var esc = false
            for (c in body) {
                when {
                    esc -> { sb.append(c); esc = false }
                    c == '\\' -> esc = true
                    c == ':' && key == null -> { key = sb.toString().uppercase(); sb.clear() }
                    c == ';' -> { key?.let { if (it !in out) out[it] = sb.toString() }; key = null; sb.clear() }
                    else -> sb.append(c)
                }
            }
            key?.let { if (it !in out) out[it] = sb.toString() }
            return out
        }

        private fun query(t: String): Map<String, String> = t.substringAfter('?', "").split('&').mapNotNull {
            val k = it.substringBefore('=').lowercase(); val v = it.substringAfter('=', "")
            if (k.isBlank()) null else k to runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }.toMap()

        private fun upi(t: String): Payload.Upi? {
            val q = query(t)
            val pa = q["pa"]?.trim()?.takeIf { Regex("^[\\w.\\-]{2,}@[a-zA-Z][\\w.]*$").matches(it) } ?: return null
            val am = q["am"]?.takeIf { Regex("^\\d+(\\.\\d{1,2})?$").matches(it) }
            return Upi(t, pa, q["pn"]?.takeIf { it.isNotBlank() }, am, q["tn"]?.takeIf { it.isNotBlank() })
        }

        private fun mailto(t: String): Payload {
            val to = t.substring(7).substringBefore('?')
            val q = query(t)
            return Email(t, to, q["subject"], q["body"])
        }

        private fun matmsg(t: String): Payload {
            val f = fields(t.substring(7))
            return Email(t, f["TO"].orEmpty(), f["SUB"], f["BODY"])
        }

        private fun sms(t: String): Payload {
            val rest = t.substringAfter(':')
            val number = rest.substringBefore(':').substringBefore('?')
            val body = rest.substringAfter(':', "").ifBlank { query(t)["body"].orEmpty() }.ifBlank { null }
            return Sms(t, number, body)
        }

        private fun geo(t: String): Payload? {
            val coords = t.substring(4).substringBefore('?').split(',')
            val lat = coords.getOrNull(0)?.toDoubleOrNull() ?: return null
            val lon = coords.getOrNull(1)?.toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return Geo(t, lat, lon, query(t)["q"])
        }

        private fun mecard(t: String): Payload {
            val f = fields(t.substring(7))
            val name = f["N"]?.split(',')?.reversed()?.joinToString(" ")?.trim()
            return Contact(t, name, f["TEL"], f["EMAIL"], f["ORG"])
        }

        private fun vcard(t: String): Payload {
            fun line(key: String) = t.lineSequence().map { it.trim() }
                .firstOrNull { it.uppercase().startsWith("$key:") || it.uppercase().startsWith("$key;") }?.substringAfter(':')?.trim()
            val name = line("FN") ?: line("N")?.split(';')?.let { p -> listOfNotNull(p.getOrNull(1), p.getOrNull(0)).joinToString(" ") }
            return Contact(t, name?.ifBlank { null }, line("TEL"), line("EMAIL"), line("ORG")?.replace(";", " ")?.trim())
        }
    }
}
