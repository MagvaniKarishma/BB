package com.brokerbuddy.core.voice

import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.Extraction
import com.brokerbuddy.core.model.Portal
import com.brokerbuddy.core.model.PortalLeadStatus
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.portal.DateFilter
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** A client the command could be about. */
data class ClientChoice(val id: String, val name: String, val phone: String)

/** A portal listing with enquiries (for "who's interested in the Andheri flat?"). */
data class ListingChoice(val id: String, val portal: Portal, val title: String?, val locality: String?, val externalId: String?)

/** Who the command is about: exactly one client, several (ask), or none found. */
sealed interface Who {
    data class One(val client: ClientChoice) : Who
    data class Several(val candidates: List<ClientChoice>) : Who
    data object Nobody : Who
}

/**
 * What a spoken or typed command means. Nothing here changes data: the app shows it for review
 * and only saves when the broker confirms. Every value comes from the words said.
 */
sealed interface VoiceCommand {
    /** "Add a new client named Rahul" — opens the new-client form with what was said. */
    data class AddClient(val name: String?, val phone: String?) : VoiceCommand

    /** "Rahul is looking for a 2 BHK in Andheri under 1 crore" — a requirement to review for that client. */
    data class AddRequirement(val who: Who, val extraction: Extraction, val nameSaid: String?) : VoiceCommand

    /** "Show me clients looking for a 2 BHK in Bandra" — the client list searched by requirement. */
    data class FindClients(val search: String, val category: PropertyCategory?, val locations: List<String>) : VoiceCommand

    /** "Schedule a follow-up with Rahul tomorrow" — a follow-up to confirm. */
    data class FollowUp(val who: Who, val at: LocalDateTime, val whenLabel: String, val timeSaid: Boolean) : VoiceCommand

    /** "Show me today's Housing.com leads". Portal null = not said (ask which). */
    data class PortalLeads(val portal: Portal?, val range: DateFilter) : VoiceCommand

    /** "Find properties under 80 lakhs in Powai" — the property list with these filters. */
    data class FindProperties(
        val transactionType: TransactionType?,
        val category: PropertyCategory?,
        val minPrice: Long?,
        val maxPrice: Long?,
        val locality: String?,
    ) : VoiceCommand

    /** "Mark Rahul as contacted" — their latest portal enquiry's status, to confirm. */
    data class LeadStatus(val who: Who, val status: PortalLeadStatus, val portal: Portal?) : VoiceCommand

    /** "Show everyone interested in the Andheri property". */
    data class ListingClients(val listings: List<ListingChoice>, val range: DateFilter) : VoiceCommand

    data class Unknown(val message: String) : VoiceCommand
}

/**
 * Understands broker commands on the phone (English, Hinglish, and common Hindi/Marathi words;
 * Hindi/Marathi speech is best passed through the phone's English translation first). A client
 * is picked only when exactly one saved name is in the command; otherwise the choices are given.
 */
object Commands {
    const val HELP = "Try: “Add a new client named Rahul”, “Rahul is looking for a 2 BHK in Andheri under 1 crore”, " +
        "“Show me clients looking for a 2 BHK in Bandra”, “Schedule a follow-up with Rahul tomorrow”, " +
        "“Show me today's Housing.com leads” or “Find properties under 80 lakhs in Powai”."

    private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))

    private val ACRES = rx("99\\s?-?\\s?acres|ninety\\s?nine\\s?acres|निन्यानवे|नाइंटी\\s?नाइन")
    private val HOUSING = rx("housing(\\.com|\\s?dot\\s?com)?|हाउसिंग|हौसिंग")
    private val LEADS = rx("\\b(leads?|enquir\\w*|inquir\\w*)\\b|लीड|पूछताछ|चौकशी")
    private val SHOW = rx("\\b(show|open|list|see|find|search|get|dikhao|dikha|batao)\\b|दिखाओ|दिखा|बताओ|दाखव")
    private val TODAY = rx("\\b(today|todays|today's|aaj|aj)\\b|आज")
    private val YESTERDAY = rx("\\b(yesterday|kal\\s?ki|kal\\s?ke|kal\\s?wali)\\b|कल\\s?(की|के|वाली)|काल(च्या|चे|ची)?")
    private val LAST7 = rx("\\b(last|past|previous|pichhle|pichle)\\s?(7|seven|saat)\\s?(days?|din)\\b|\\b(this|last)\\s?week\\b|पिछले\\s?(7|सात)\\s?दिन|मागील\\s?(7|सात)\\s?दिवस")

    private val STATUS = listOf(
        PortalLeadStatus.NOT_INTERESTED to rx("\\bnot\\s?interested\\b|\\binterest(ed)?\\s?nahi\\b|रुचि\\s?नहीं|इच्छुक\\s?नाही"),
        PortalLeadStatus.CONVERTED to rx("\\bconverted\\b|\\bdeal\\s?(done|ho\\s?gayi|final)\\b"),
        PortalLeadStatus.CONTACTED to rx("\\bcontacted\\b|\\bsampark\\b|\\bbaat\\s?ho\\s?gayi\\b|संपर्क|बात\\s?हो\\s?गई"),
        PortalLeadStatus.CLOSED to rx("\\bclosed\\b"),
    )
    private val MARK = rx("\\bmark\\b|\\bstatus\\b|\\bas\\b|मार्क|कर\\s?दो|करो")
    private val FOLLOW_UP = rx("\\bfollow[\\s-]?up\\b|\\bremind(er)?\\b|\\bcall\\s?back\\b|फॉलो[\\s-]?अप|रिमाइंडर")
    private val TOMORROW = rx("\\b(tomorrow|kal|kl|udya)\\b|कल|उद्या")
    private val DAY_AFTER = rx("\\b(day after tomorrow|parso|parson)\\b|परसों|परवा")
    private val TIME = rx("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|baje|o'?clock)\\b|\\bat\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\b|(\\d{1,2})\\s*(बजे|वाजता)")
    private val EVENING = rx("\\b(evening|shaam|sham|night|raat)\\b|शाम|रात|संध्याकाळ")

    private val ADD_CLIENT = rx("\\b(add|create|new|save|naya|nayi|register)\\b[^.]*?\\b(client|customer|lead|contact)\\b|नया\\s?(क्लाइंट|ग्राहक)|नवीन\\s?(क्लायंट|ग्राहक)")
    private val NAMED = rx("\\b(?:named|called|name is|naam|name)\\s+(.+)$|\\b(?:client|customer|contact|lead)\\s+(?!named|called|name)(.+)$|(?:क्लाइंट|ग्राहक|क्लायंट)\\s+(.+)$")
    private val NAME_STOP = rx("\\s*(?:\\bwith\\b|\\bphone\\b|\\bnumber\\b|\\bmobile\\b|\\bwho\\b|\\bis looking\\b|\\blooking\\b|\\bwants\\b|\\band\\b|,|\\.|\\bka\\b|\\bki\\b|\\d).*$")
    private val PHONE = rx("(?:\\+?91[\\s-]?)?([6-9]\\d{4}[\\s-]?\\d{5})")
    private val CLIENTS_WORD = rx("\\bclients?\\b|\\bcustomers?\\b|\\bbuyers?\\b|\\btenants?\\b|\\bpeople\\b|\\bwho\\b|कौन|क्लाइंट|ग्राहक")
    private val PROPERTIES_WORD = rx("\\bpropert(y|ies)\\b|\\bflats?\\b|\\bapartments?\\b|\\blistings?\\b|\\bhomes?\\b|\\binventory\\b|प्रॉपर्टी|फ्लैट|फ्लॅट")
    private val WANTS = rx("\\b(looking for|looking|wants?|needs?|searching for|require[sd]?|interested in buying|chahiye|chaiye|dhundh)\\b|चाहिए|हवा|हवी|हवे|शोधत")
    private val INTERESTED = rx("\\binterested\\b|\\bintrested\\b|रुचि|इंटरेस्ट|इच्छुक")

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).trim()

    /** Whole word, any case; Latin and Devanagari (Hindi/Marathi suffixes like "को", "ला", "सोबत" allowed). */
    fun containsName(text: String, name: String): Boolean =
        Regex("(^|[^\\p{L}\\p{M}])${Regex.escape(name)}(?=$|[^\\p{L}\\p{M}]|ला|ना|सोबत|च्या|ची|चे|को|के|की|से|'s)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    /** Exactly one client whose full name — else first name — is in the command; otherwise the candidates. */
    fun findClient(text: String, clients: List<ClientChoice>): Who {
        val full = clients.filter { it.name.trim().length >= 3 && containsName(text, it.name.trim()) }
        if (full.size == 1) return Who.One(full.first())
        val pool = if (full.size > 1) full else clients
        val first = pool.filter { c -> c.name.trim().split(Regex("\\s+")).first().let { it.length >= 2 && containsName(text, it) } }
        return when (first.size) {
            0 -> Who.Nobody
            1 -> Who.One(first.first())
            else -> Who.Several(first)
        }
    }

    private fun portalOf(text: String): Portal? {
        val a = ACRES.containsMatchIn(text)
        val h = HOUSING.containsMatchIn(text)
        return if (a && !h) Portal.ACRES_99 else if (h && !a) Portal.HOUSING_COM else null
    }

    private fun rangeOf(text: String): DateFilter? = when {
        LAST7.containsMatchIn(text) -> DateFilter.LAST_7_DAYS
        YESTERDAY.containsMatchIn(text) -> DateFilter.YESTERDAY
        TODAY.containsMatchIn(text) -> DateFilter.TODAY
        else -> null
    }

    fun parse(
        raw: String,
        clients: List<ClientChoice>,
        now: ZonedDateTime,
        listings: List<ListingChoice> = emptyList(),
    ): VoiceCommand {
        val text = norm(raw)
        if (text.isEmpty()) return VoiceCommand.Unknown(HELP)
        val portal = portalOf(text)
        val wantsLeads = LEADS.containsMatchIn(text)

        // --- lead status ---
        val status = STATUS.firstOrNull { (_, re) -> re.containsMatchIn(text) }?.first
        if (status != null && MARK.containsMatchIn(text)) {
            return VoiceCommand.LeadStatus(findClient(text, clients), status, portal)
        }

        // --- follow-up ---
        if (FOLLOW_UP.containsMatchIn(text) && !(wantsLeads && SHOW.containsMatchIn(text))) {
            val day = if (DAY_AFTER.containsMatchIn(text)) 2 else if (TOMORROW.containsMatchIn(text)) 1 else 0
            val t = TIME.find(text.replace(ACRES, ""))
            var hh = 10
            var mm = 0
            if (t != null) {
                val g = t.groupValues
                hh = (g[1].ifEmpty { g[4] }.ifEmpty { g[6] }).toInt()
                mm = (g[2].ifEmpty { g[5] }).ifEmpty { "0" }.toInt()
                val ap = g[3].lowercase().replace(".", "")
                when {
                    ap == "pm" && hh < 12 -> hh += 12
                    ap == "am" && hh == 12 -> hh = 0
                    ap.isEmpty() || ap == "baje" || ap.startsWith("o") -> if (hh < 8 || EVENING.containsMatchIn(text)) { if (hh < 12) hh += 12 }
                }
                if (hh > 23 || mm > 59) { hh = 10; mm = 0 }
            }
            var at = now.toLocalDate().plusDays(day.toLong()).atTime(hh, mm)
            // "Today" at a time already past: in an hour.
            if (!at.isAfter(now.toLocalDateTime())) at = now.toLocalDateTime().plusHours(1).withSecond(0).withNano(0)
            val dayLabel = when {
                at.toLocalDate() == now.toLocalDate() -> "today"
                at.toLocalDate() == now.toLocalDate().plusDays(1) -> "tomorrow"
                else -> "the day after tomorrow"
            }
            val label = "$dayLabel at %02d:%02d".format(at.hour, at.minute)
            return VoiceCommand.FollowUp(findClient(text, clients), at, label, t != null)
        }

        // --- add a client ---
        if (ADD_CLIENT.containsMatchIn(text) && !SHOW.containsMatchIn(text.substringBefore("client"))) {
            val phone = PHONE.find(text)?.groupValues?.get(1)?.filter(Char::isDigit)
            val named = NAMED.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            val name = named?.replace(NAME_STOP, "")?.trim()?.trim(',', '.', '"', '“', '”')
                ?.split(Regex("\\s+"))?.take(4)?.joinToString(" ")
                ?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it.length >= 2 && !it.equals("named", true) }
            return VoiceCommand.AddClient(name, phone)
        }

        val extraction = PhoneRules.extract(text)
        val d = extraction.draft
        val saysRequirement = d.category != null || d.budgetMax != null || d.budgetMin != null || !d.locations.isNullOrEmpty() || d.transactionType != null

        // --- properties with filters ---
        if (PROPERTIES_WORD.containsMatchIn(text) && !INTERESTED.containsMatchIn(text) && !wantsLeads) {
            return VoiceCommand.FindProperties(
                d.transactionType?.value, d.category?.value, d.budgetMin?.value, d.budgetMax?.value,
                d.locations?.firstOrNull()?.value,
            )
        }

        // --- clients looking for something ---
        if (CLIENTS_WORD.containsMatchIn(text) && SHOW.containsMatchIn(text) && saysRequirement && !wantsLeads && portal == null) {
            val locations = d.locations.orEmpty().map { it.value }
            val search = listOfNotNull(d.category?.value?.label, locations.firstOrNull()).joinToString(" ")
            return VoiceCommand.FindClients(search, d.category?.value, locations)
        }

        // --- everyone interested in a listing ---
        if (INTERESTED.containsMatchIn(text) && (PROPERTIES_WORD.containsMatchIn(text) || listings.any { l -> l.locality?.let { containsName(text, it) } == true })) {
            val hits = listings.filter { l ->
                (portal == null || l.portal == portal) &&
                    (listOfNotNull(l.locality, l.title, l.externalId).any { f -> f.trim().length >= 3 && containsName(text, f.trim()) } ||
                        (l.locality ?: "").split(Regex("[\\s,]+")).any { w -> w.length >= 4 && containsName(text, w) })
            }
            return VoiceCommand.ListingClients(hits, rangeOf(text) ?: DateFilter.LAST_7_DAYS)
        }

        // --- a requirement for a client ---
        if (saysRequirement && (WANTS.containsMatchIn(text) || clients.any { containsName(text, it.name) })) {
            val who = findClient(text, clients)
            val nameSaid = Regex("^\\s*([\\p{L}\\p{M}]+(?:\\s+[\\p{L}\\p{M}]+)?)\\s+(?:is|are|ko|ne|wants?|needs?|looking)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)
            return VoiceCommand.AddRequirement(who, extraction, nameSaid)
        }

        // --- portal leads for a day ---
        if (portal != null || wantsLeads) return VoiceCommand.PortalLeads(portal, rangeOf(text) ?: DateFilter.TODAY)

        return VoiceCommand.Unknown("Sorry, I didn't understand that. $HELP")
    }

    /** One line saying what the command will do, for the review card. */
    fun describe(c: VoiceCommand): String = when (c) {
        is VoiceCommand.AddClient -> "New client" + (c.name?.let { ": $it" } ?: "") + (c.phone?.let { " · $it" } ?: "") +
            (if (c.name == null) " — say or type the name in the form" else "")
        is VoiceCommand.AddRequirement -> "New requirement" + ((c.who as? Who.One)?.client?.name?.let { " for $it" } ?: "")
        is VoiceCommand.FindClients -> "Clients looking for ${c.search.ifBlank { "this" }}"
        is VoiceCommand.FollowUp -> "Follow-up" + ((c.who as? Who.One)?.client?.name?.let { " with $it" } ?: "") + " ${c.whenLabel}"
        is VoiceCommand.PortalLeads -> (c.portal?.label ?: "Portal") + " leads — " + c.range.label.lowercase()
        is VoiceCommand.FindProperties -> "Properties" + listOfNotNull(
            c.category?.label?.let { " · $it" }, c.transactionType?.let { " · for ${it.label.lowercase()}" },
            c.locality?.let { " in $it" }, Money.range(c.minPrice, c.maxPrice)?.let { " · $it" },
        ).joinToString("")
        is VoiceCommand.LeadStatus -> "Mark" + ((c.who as? Who.One)?.client?.name?.let { " $it's latest enquiry" } ?: " the enquiry") + " as ${c.status.label}"
        is VoiceCommand.ListingClients -> "Clients interested in a listing"
        is VoiceCommand.Unknown -> c.message
    }
}
