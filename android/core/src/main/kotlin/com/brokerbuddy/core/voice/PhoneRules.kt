package com.brokerbuddy.core.voice

import com.brokerbuddy.core.model.Evidence
import com.brokerbuddy.core.model.Extraction
import com.brokerbuddy.core.model.FloorBand
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.RequirementDraft
import com.brokerbuddy.core.model.TransactionType
import java.text.Normalizer

/**
 * Reads a requirement from spoken or typed words on the phone (used when there's no server, i.e.
 * the demo). A smaller version of the server's rules (backend/src/voice/rulesExtractor.ts):
 * English and Hinglish, plus common Hindi/Marathi words. Hindi or Marathi speech is best read
 * after the app translates it to English on the phone.
 *
 * The rule is the same as the server's: only what was actually said is filled in, each value
 * with the words it came from; nothing is guessed. Anything unclear becomes a warning.
 */
object PhoneRules {
    const val NAME = "rules (on phone)"

    private val NUMBER_WORDS = mapOf(
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0,
        "nine" to 9.0, "ten" to 10.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0, "fifty" to 50.0, "sixty" to 60.0,
        "seventy" to 70.0, "eighty" to 80.0, "ninety" to 90.0, "hundred" to 100.0,
        "ek" to 1.0, "do" to 2.0, "teen" to 3.0, "char" to 4.0, "chaar" to 4.0, "paanch" to 5.0, "das" to 10.0, "bees" to 20.0,
        "pachaas" to 50.0, "pachas" to 50.0, "sattar" to 70.0, "assi" to 80.0, "dedh" to 1.5, "dhai" to 2.5,
        "एक" to 1.0, "दो" to 2.0, "तीन" to 3.0, "चार" to 4.0, "पांच" to 5.0, "पाँच" to 5.0, "दस" to 10.0, "बीस" to 20.0,
        "पचास" to 50.0, "सत्तर" to 70.0, "अस्सी" to 80.0, "डेढ़" to 1.5, "ढाई" to 2.5,
        "दोन" to 2.0, "पाच" to 5.0, "पन्नास" to 50.0, "ऐंशी" to 80.0, "दीड" to 1.5, "अडीच" to 2.5,
    )

    private val UNITS = listOf(
        listOf("crores", "crore", "cr", "karod", "करोड़", "करोड", "कोटी") to 10_000_000.0,
        listOf("lakhs", "lakh", "lacs", "lac", "l", "लाख") to 100_000.0,
        listOf("thousand", "hazaar", "hazar", "k", "हज़ार", "हजार") to 1_000.0,
    )

    /** Mumbai areas: canonical name → ways it's said or written. */
    val LOCALITIES: Map<String, List<String>> = linkedMapOf(
        "Andheri" to listOf("andheri", "अंधेरी"), "Bandra Kurla Complex" to listOf("bkc", "bandra kurla complex"),
        "Bandra" to listOf("bandra", "बांद्रा", "वांद्रे"), "Khar" to listOf("khar", "खार"), "Santacruz" to listOf("santacruz", "santa cruz"),
        "Vile Parle" to listOf("vile parle", "vileparle"), "Juhu" to listOf("juhu", "जुहू"), "Versova" to listOf("versova"),
        "Lokhandwala" to listOf("lokhandwala"), "Oshiwara" to listOf("oshiwara"), "Jogeshwari" to listOf("jogeshwari", "जोगेश्वरी"),
        "Goregaon" to listOf("goregaon", "गोरेगांव", "गोरेगाव"), "Malad" to listOf("malad", "मलाड", "मालाड"),
        "Kandivali" to listOf("kandivali", "kandivli", "कांदिवली"), "Borivali" to listOf("borivali", "borivli", "बोरीवली", "बोरिवली"),
        "Dahisar" to listOf("dahisar"), "Mira Road" to listOf("mira road", "mira rd"), "Powai" to listOf("powai", "पवई"),
        "Chandivali" to listOf("chandivali"), "Ghatkopar" to listOf("ghatkopar", "घाटकोपर"), "Vikhroli" to listOf("vikhroli"),
        "Bhandup" to listOf("bhandup"), "Mulund" to listOf("mulund", "मुलुंड"), "Kurla" to listOf("kurla", "कुर्ला"),
        "Chembur" to listOf("chembur", "चेंबूर"), "Sion" to listOf("sion", "सायन"), "Wadala" to listOf("wadala"),
        "Dadar" to listOf("dadar", "दादर"), "Matunga" to listOf("matunga"), "Mahim" to listOf("mahim"), "Prabhadevi" to listOf("prabhadevi"),
        "Worli" to listOf("worli", "वर्ली", "वरळी"), "Lower Parel" to listOf("lower parel"), "Parel" to listOf("parel", "परेल"),
        "Byculla" to listOf("byculla"), "Colaba" to listOf("colaba"), "Malabar Hill" to listOf("malabar hill"), "Thane" to listOf("thane", "ठाणे"),
        "Navi Mumbai" to listOf("navi mumbai"), "Vashi" to listOf("vashi", "वाशी"), "Kharghar" to listOf("kharghar"),
        "Panvel" to listOf("panvel"), "Airoli" to listOf("airoli"), "Dombivli" to listOf("dombivli"), "Kalyan" to listOf("kalyan"),
        "Madh Island" to listOf("madh island", "madh"),
    )

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)
    private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))
    private fun alt(words: Collection<String>) = words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    /** Word boundaries that also work for Devanagari. */
    private const val L = "(?<![\\p{L}\\p{M}\\d])"
    private const val R = "(?![\\p{L}\\p{M}])"

    /** Digits (a unit may follow directly: "80L", "1.2cr") or a whole number word. */
    private val NUM = "$L(\\d+(?:\\.\\d+)?|(?:${alt(NUMBER_WORDS.keys)})$R)"
    private val UNIT = "(${alt(UNITS.flatMap { it.first })})"
    private val MONEY = "₹?\\s*$NUM(?:\\s*$UNIT$R)?"

    fun parseNumber(s: String): Double? = s.replace(",", "").toDoubleOrNull() ?: NUMBER_WORDS[s.lowercase()]
    private fun unitOf(u: String?): Double? = u?.let { w -> UNITS.firstOrNull { (words, _) -> words.any { it.equals(w, ignoreCase = true) } }?.second }

    fun extract(raw: String): Extraction {
        val text = norm(raw).replace(Regex("(\\d),(\\d)"), "$1$2")
        val warnings = mutableListOf<String>()
        fun <T> ev(v: T, m: MatchResult) = Evidence(v, m.value.trim())

        // Rent / buy: only when said.
        val rent = rx("$L(rent|rental|on rent|kiraye?|kiraya|lease|किराए|किराये|किराया|भाड्याने|भाडे)$R").find(text)
        val buy = rx("$L(buy|buying|purchase|kharid\\w*|khareed\\w*|खरीद\\w*|विकत)$R").find(text)
        val transactionType = when {
            rent != null && buy != null -> { warnings += "Both rent and buy were mentioned — choose one"; null }
            rent != null -> ev(TransactionType.RENT, rent)
            buy != null -> ev(TransactionType.BUY, buy)
            else -> null
        }

        // Size.
        val sizes = mutableListOf<Pair<PropertyCategory, MatchResult>>()
        rx("$L$NUM\\s*-?\\s*(bhk|bedroom|bed room|बीएचके)$R").findAll(text).forEach { m ->
            val n = parseNumber(m.groupValues[1])
            val cat = when {
                n == null || n != Math.floor(n) -> null
                n.toInt() == 1 -> PropertyCategory.BHK_1
                n.toInt() == 2 -> PropertyCategory.BHK_2
                n.toInt() == 3 -> PropertyCategory.BHK_3
                n.toInt() == 4 -> PropertyCategory.BHK_4
                n.toInt() >= 5 -> PropertyCategory.BHK_5_PLUS
                else -> null
            }
            if (cat != null) sizes += cat to m else warnings += "Read \"${m.value.trim()}\" — choose the BHK"
        }
        rx("$L(1\\s*rk|one\\s*rk|studio)$R").findAll(text).forEach { sizes += PropertyCategory.STUDIO to it }
        rx("$L(office|shop|dukaan|showroom|commercial|godown|दुकान|ऑफिस)$R").findAll(text).forEach { sizes += PropertyCategory.COMMERCIAL to it }
        // "2 or 3 BHK", "2-3 BHK": more than one size is acceptable — the broker chooses.
        val eitherSize = rx("$L$NUM\\s*(?:or|ya|/|to|-|–|,)\\s*$NUM\\s*-?\\s*(bhk|bedroom)$R").find(text)
        if (eitherSize != null) {
            warnings += "More than one size was mentioned (\"${eitherSize.value.trim()}\") — choose one"
            sizes.clear()
        }
        val category = when (sizes.map { it.first }.distinct().size) {
            0 -> null
            1 -> ev(sizes.first().first, sizes.first().second)
            else -> { warnings += "More than one size was mentioned (${sizes.joinToString { it.second.value.trim() }}) — choose one"; null }
        }

        // Budget.
        fun amount(n: String, u: String?, fallbackUnit: String? = null): Long? {
            val v = parseNumber(n) ?: return null
            val mult = unitOf(u) ?: unitOf(fallbackUnit) ?: return if (v >= 1000) v.toLong() else null
            return (v * mult).toLong()
        }
        var budgetMin: Evidence<Long>? = null
        var budgetMax: Evidence<Long>? = null
        rx("$L(?:between\\s+|from\\s+)?$MONEY\\s*(?:to|-|–|se|and|तक|ते)\\s*$MONEY(?:\\s*(?:tak|तक))?").find(text)?.let { m ->
            val lo = amount(m.groupValues[1], m.groupValues[2].ifEmpty { null }, m.groupValues[4].ifEmpty { null })
            val hi = amount(m.groupValues[3], m.groupValues[4].ifEmpty { null })
            if (lo != null && hi != null && m.groupValues[4].isNotEmpty()) {
                if (lo <= hi) { budgetMin = ev(lo, m); budgetMax = ev(hi, m) } else warnings += "Budget range \"${m.value.trim()}\" is reversed — check it"
            }
        }
        if (budgetMax == null) {
            rx("$L(?:under|below|upto|up to|within|max(?:imum)?|less than|not more than|budget(?: of| is)?(?: under)?)\\s*$MONEY").find(text)?.let { m ->
                amount(m.groupValues[1], m.groupValues[2].ifEmpty { null })?.let { budgetMax = ev(it, m) }
            }
            ?: rx("$MONEY\\s*(?:tak|तक|पर्यंत|max|maximum|or less|se kam|से कम)$R").find(text)?.let { m ->
                amount(m.groupValues[1], m.groupValues[2].ifEmpty { null })?.let { budgetMax = ev(it, m) }
            }
        }
        if (budgetMin == null) {
            rx("$L(?:above|over|at least|minimum|min|kam se kam|कम से कम)\\s*$MONEY").find(text)?.let { m ->
                amount(m.groupValues[1], m.groupValues[2].ifEmpty { null })?.let { budgetMin = ev(it, m) }
            }
        }
        if (budgetMin == null && budgetMax == null) {
            rx("$L(?:budget|बजट)\\s*(?:is|of|hai|है)?\\s*(?:around|about|approx)?\\s*$MONEY").find(text)?.let { m ->
                amount(m.groupValues[1], m.groupValues[2].ifEmpty { null })?.let {
                    budgetMax = ev(it, m)
                    warnings += "Read \"${m.value.trim()}\" as the maximum budget"
                }
            }
        }

        // Areas (East/West kept when said); "not X" is skipped.
        val locations = mutableListOf<Evidence<String>>()
        val claimed = mutableListOf<IntRange>()
        // Longer names first, so "Bandra Kurla Complex" isn't also read as "Bandra".
        for ((name, variants) in LOCALITIES.entries.sortedByDescending { e -> e.value.maxOf { it.length } }) {
            val m = rx("$L(${alt(variants)})(?:\\s*(east|west|\\(e\\)|\\(w\\)|e\\b|w\\b|पूर्व|पश्चिम))?$R").findAll(text)
                .firstOrNull { m -> claimed.none { it.first <= m.range.last && m.range.first <= it.last } } ?: continue
            claimed += m.range
            val after = text.substring(m.range.last + 1).take(15)
            val before = text.substring(0, m.range.first).takeLast(14)
            if (rx("^\\s*(nahi|nahin|not|mat|नहीं|नको)").containsMatchIn(after) || rx("(not|no|except|but not|नहीं)(\\s+(in|at|near))?\\s*$").containsMatchIn(before)) {
                warnings += "Skipped \"${m.value.trim()}\": said as not wanted"
                continue
            }
            val dir = m.groupValues[2].lowercase().let {
                when {
                    it.startsWith("e") || it == "(e)" || it == "पूर्व" -> " East"
                    it.startsWith("w") || it == "(w)" || it == "पश्चिम" -> " West"
                    else -> ""
                }
            }
            locations += Evidence(name + dir, m.value.trim())
        }
        // In the order they were said.
        val locs = locations.sortedBy { l -> text.indexOf(l.evidence) }

        // Furnishing.
        val furnishing = rx("$L(fully[\\s-]?furnished|semi[\\s-]?furnished|un[\\s-]?furnished|furnished)$R").findAll(text).toList().let { ms ->
            if (ms.isEmpty()) null else {
                val kinds = ms.map { m ->
                    val w = m.value.lowercase()
                    when {
                        w.startsWith("semi") -> Furnishing.SEMI_FURNISHED
                        w.startsWith("un") -> Furnishing.UNFURNISHED
                        w.startsWith("fully") -> Furnishing.FULLY_FURNISHED
                        else -> { warnings += "Read \"furnished\" as fully furnished"; Furnishing.FULLY_FURNISHED }
                    }
                }.distinct()
                Evidence(kinds, ms.first().value.trim())
            }
        }

        // Parking.
        val parking = rx("$L$NUM\\s*(?:car\\s*)?parkings?$R").find(text)?.let { m -> parseNumber(m.groupValues[1])?.toInt()?.takeIf { it in 1..10 }?.let { ev(it, m) } }
            ?: rx("$L(?:car\\s*)?parking\\s*(?:chahiye|chaiye|needed|required|zaroori|must|चाहिए|हवी)$R|$L(?:needs?|wants?|with)\\s+(?:a\\s+)?(?:car\\s+)?parking$R").find(text)?.let { ev(1, it) }

        // Floor: lower / middle / higher only; exact floors are only mentioned in a warning.
        val bands = mutableListOf<Pair<FloorBand, MatchResult>>()
        rx("$L(high|higher|upper|top|upar wal[ae]|ऊंची|ऊँची|ऊपर वाला)\\s*(floor|floors|मंजिल|माळा)$R").findAll(text).forEach { bands += FloorBand.HIGHER to it }
        rx("$L(middle|mid|beech ka|बीच की|मधला)\\s*(floor|floors|मंजिल|माळा)$R").findAll(text).forEach { bands += FloorBand.MIDDLE to it }
        rx("$L(low|lower|ground|neeche wal[ae]|नीचे वाला)\\s*(floor|floors|मंजिल)$R|${L}तळमजला$R").findAll(text).forEach { bands += FloorBand.LOWER to it }
        val floorPreference = if (bands.isEmpty()) null else Evidence(bands.map { it.first }.distinct(), bands.first().second.value.trim())
        rx("$L(\\d+)(st|nd|rd|th)?\\s*(floor|मंजिल)\\s*(ke upar|above|tak|or above|se upar)?").findAll(text).forEach {
            warnings += "Mentioned \"${it.value.trim()}\" — choose Lower / Middle / Higher floor if it matters (exact floors aren't saved)"
        }

        // Possession.
        val possession = rx("$L(ready to move|ready possession|ready flat|रेडी पजेशन)$R").find(text)?.let { ev(Possession.READY_TO_MOVE, it) }
            ?: rx("$L(under construction|under-construction|निर्माणाधीन)$R").find(text)?.let { ev(Possession.UNDER_CONSTRUCTION, it) }

        return Extraction(
            draft = RequirementDraft(
                transactionType = transactionType, category = category, budgetMin = budgetMin, budgetMax = budgetMax,
                locations = locs.takeIf { it.isNotEmpty() }, furnishing = furnishing, minParking = parking,
                floorPreference = floorPreference, possession = possession,
            ),
            warnings = warnings.distinct(),
            extractor = NAME,
        )
    }
}
