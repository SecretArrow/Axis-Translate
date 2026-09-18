package com.axis.translate.domain

import com.axis.translate.domain.model.Language

/**
 * Fully local [LanguageDetector] (SPEC #31) — no network, no downloads.
 *
 * Strategy:
 * 1. Script-first detection via [Character.UnicodeBlock]. If more than 30% of
 *    the letters belong to one non-Latin script, that script's language wins.
 * 2. Latin-script fallback via stopword tables over the languages of
 *    [Language.FALLBACK_CATALOG] that are written in Latin script, boosted by
 *    characteristic diacritics.
 */
class HeuristicLanguageDetector : LanguageDetector {

    override fun detect(text: String): LanguageDetection? {
        if (text.isBlank()) return null

        // --- 1. Script detection -------------------------------------------------
        var totalLetters = 0
        val scriptHits = IntArray(scriptRules.size)
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (Character.isLetter(codePoint)) totalLetters++
            val block = Character.UnicodeBlock.of(codePoint)
            if (block != null) {
                for (r in scriptRules.indices) {
                    if (block in scriptRules[r].blocks) scriptHits[r]++
                }
            }
            i += Character.charCount(codePoint)
        }

        if (totalLetters > 0) {
            for (r in scriptRules.indices) {
                val hits = scriptHits[r]
                if (hits > 0) {
                    val ratio = hits.toFloat() / totalLetters
                    if (ratio > SCRIPT_RATIO_THRESHOLD) {
                        return LanguageDetection(
                            language = scriptRules[r].language,
                            confidence = ratio.coerceAtMost(MAX_SCRIPT_CONFIDENCE),
                        )
                    }
                }
            }
        }

        // --- 2. Latin stopwords ---------------------------------------------------
        val lower = text.lowercase()
        val words = lower.split(tokenSeparator).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val bonuses = diacriticBonuses(lower)
        var bestIndex = -1
        var bestScore = 0f
        val scores = FloatArray(latinStopwords.size)
        for (langIndex in latinStopwords.indices) {
            val table = latinStopwords[langIndex].stopwords
            var matched = 0
            for (word in words) {
                if (word in table) matched++
            }
            val score = matched.toFloat() / words.size
            scores[langIndex] = score
            if (score > bestScore) {
                bestScore = score
                bestIndex = langIndex
            }
        }
        if (bestIndex < 0 || bestScore <= 0f) return null

        val winner = latinStopwords[bestIndex]
        val alternatives = latinStopwords
            .withIndex()
            .filter { (index, _) -> index != bestIndex && scores[index] > 0f }
            .sortedByDescending { (index, _) -> scores[index] }
            .map { (_, entry) -> entry.language }
            .take(MAX_ALTERNATIVES)

        val confidence = (bestScore * 0.75f + (bonuses[winner.code] ?: 0f))
            .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)

        return LanguageDetection(
            language = winner.language,
            confidence = confidence,
            alternatives = alternatives,
        )
    }

    /** Non-Latin scripts we can identify unambiguously by Unicode block. */
    private data class ScriptRule(
        val language: Language,
        val blocks: Set<Character.UnicodeBlock>,
    )

    private data class LatinRule(
        val code: String,
        val language: Language,
        val stopwords: Set<String>,
    )

    private companion object {
        const val SCRIPT_RATIO_THRESHOLD = 0.3f
        const val MAX_SCRIPT_CONFIDENCE = 0.98f
        const val MIN_CONFIDENCE = 0.35f
        const val MAX_CONFIDENCE = 0.95f
        const val MAX_ALTERNATIVES = 2

        /** Splits on anything that is not a letter, mark, or apostrophe. */
        val tokenSeparator = Regex("[^\\p{L}\\p{M}']+")

        val JAPANESE = Language.byCode("ja") ?: Language("ja", "Japanese")
        val KOREAN = Language.byCode("ko") ?: Language("ko", "Korean")
        val CHINESE = Language.byCode("zh") ?: Language("zh", "Chinese (Simplified)")
        val HINDI = Language.byCode("hi") ?: Language("hi", "Hindi")
        val THAI = Language.byCode("th") ?: Language("th", "Thai")
        val ARABIC = Language.byCode("ar") ?: Language("ar", "Arabic")
        val RUSSIAN = Language.byCode("ru") ?: Language("ru", "Russian")

        fun language(code: String): Language =
            Language.byCode(code) ?: Language(code, code)

        val scriptRules = listOf(
            ScriptRule(
                JAPANESE,
                setOf(
                    Character.UnicodeBlock.HIRAGANA,
                    Character.UnicodeBlock.KATAKANA,
                    Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
                ),
            ),
            ScriptRule(
                KOREAN,
                setOf(
                    Character.UnicodeBlock.HANGUL_SYLLABLES,
                    Character.UnicodeBlock.HANGUL_JAMO,
                    Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
                    Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A,
                    Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B,
                ),
            ),
            ScriptRule(
                CHINESE,
                setOf(
                    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
                    Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
                ),
            ),
            ScriptRule(HINDI, setOf(Character.UnicodeBlock.DEVANAGARI)),
            ScriptRule(THAI, setOf(Character.UnicodeBlock.THAI)),
            ScriptRule(ARABIC, setOf(Character.UnicodeBlock.ARABIC)),
            ScriptRule(
                RUSSIAN,
                setOf(
                    Character.UnicodeBlock.CYRILLIC,
                    Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY,
                    Character.UnicodeBlock.CYRILLIC_EXTENDED_A,
                    Character.UnicodeBlock.CYRILLIC_EXTENDED_B,
                ),
            ),
        )

        /**
         * Latin-script stopword tables (12+ frequent function words each).
         * Order matters for ties: earlier tables win (e.g. id before ms).
         */
        val latinStopwords = listOf(
            "en" to setOf(
                "the", "and", "that", "have", "for", "not", "with", "you", "this",
                "but", "his", "from", "they", "will", "would", "there", "their",
                "what", "about", "which", "when", "make", "like", "time", "just",
                "know", "take", "into", "year", "your", "good", "some", "could",
                "them", "other", "than", "then", "look", "only", "come", "over",
                "think", "also", "after", "work", "first", "even", "want", "because",
                "these", "while", "being", "does", "each",
            ),
            "id" to setOf(
                "yang", "dan", "di", "itu", "dengan", "untuk", "tidak", "ini",
                "dari", "dalam", "akan", "ke", "saya", "kamu", "kami", "kita",
                "mereka", "sudah", "apa", "siapa", "bagaimana", "kalau", "tetapi",
                "bisa", "ada", "pada", "saat", "oleh", "atau", "jika", "agar",
                "serta", "yaitu", "adalah", "karena", "belum", "hanya", "olehnya",
            ),
            "es" to setOf(
                "el", "la", "los", "las", "de", "que", "y", "a", "en", "un",
                "una", "por", "con", "para", "es", "del", "se", "no", "su",
                "como", "pero", "más", "este", "esta", "todo", "muy", "sobre",
                "me", "ya", "o", "si", "cuando", "porque", "qué", "también",
                "fue", "eran", "está", "son", "ha", "han", "ser", "mío", "tuya",
            ),
            "fr" to setOf(
                "le", "la", "les", "de", "des", "et", "à", "un", "une", "du",
                "en", "que", "qui", "est", "pour", "dans", "pas", "sur", "plus",
                "je", "tu", "il", "elle", "nous", "vous", "ils", "ce", "cette",
                "avec", "mais", "ou", "où", "son", "sa", "ses", "ne", "au", "aux",
                "comme", "tout", "être", "avoir", "était", "ça", "très", "bien",
            ),
            "de" to setOf(
                "der", "die", "das", "und", "in", "den", "von", "zu", "mit",
                "sich", "des", "auf", "für", "ist", "im", "dem", "nicht", "ein",
                "eine", "als", "auch", "es", "werden", "wird", "aus", "er",
                "hat", "dass", "sie", "nach", "bei", "um", "am", "sind", "noch",
                "wenn", "war", "durch", "wie", "wir", "ihre", "doch", "gegen",
            ),
            "it" to setOf(
                "il", "la", "di", "che", "e", "a", "un", "una", "per", "in",
                "con", "del", "dei", "non", "sono", "si", "le", "al", "lo",
                "come", "ma", "anche", "più", "della", "gli", "nel", "suo",
                "essere", "avere", "questo", "quella", "quando", "dove",
                "perché", "molto", "così", "tutte", "tutti", "senza", "poi",
            ),
            "pt" to setOf(
                "o", "a", "os", "as", "de", "que", "e", "do", "da", "em",
                "um", "uma", "para", "com", "não", "por", "mais", "como",
                "mas", "ao", "na", "se", "ele", "ela", "eles", "elas", "isso",
                "este", "esta", "quando", "muito", "já", "entre", "também",
                "ser", "está", "são", "foi", "sua", "seu", "pelo", "pela",
            ),
            "nl" to setOf(
                "de", "het", "een", "en", "van", "is", "dat", "op", "te",
                "zijn", "met", "voor", "niet", "aan", "hij", "ze", "wij",
                "jullie", "zij", "dit", "deze", "wat", "maar", "om", "ook",
                "als", "dan", "nog", "was", "heeft", "hebben", "kunnen",
                "zullen", "haar", "mijn", "jouw", "ons", "hier", "hoe", "nu",
            ),
            "pl" to setOf(
                "i", "w", "na", "z", "że", "do", "o", "jak", "nie", "jest",
                "to", "się", "tak", "ale", "od", "tego", "dla", "czy", "też",
                "bardzo", "wtedy", "kiedy", "gdzie", "jestem", "jesteś", "może",
                "będą", "był", "była", "bez", "tym", "tylko", "już", "jeszcze",
                "przez", "nad", "taki", "jakie", "wszystko", "dobrze",
            ),
            "tr" to setOf(
                "ve", "bir", "bu", "için", "ile", "gibi", "ama", "daha",
                "çok", "var", "yok", "ben", "sen", "o", "biz", "siz", "onlar",
                "ne", "nasıl", "değil", "kadar", "sonra", "her", "şey",
                "olduğunu", "olduğu", "ise", "ya", "hem", "de", "da", "mi",
                "mı", "çok", "kez", "şimdi", "önce", "bana", "sana", "bunu",
            ),
            "ms" to setOf(
                "yang", "dan", "di", "itu", "dengan", "untuk", "tidak", "ini",
                "dari", "dalam", "akan", "ke", "saya", "kamu", "dia", "kami",
                "mereka", "ada", "boleh", "sudah", "pada", "atau", "jika",
                "tetapi", "kerana", "sangat", "juga", "hanya", "oleh", "ialah",
                "bila", "apa", "siapa", "kenapa", "macam", "lagi", "semua",
            ),
            "sv" to setOf(
                "och", "att", "det", "som", "en", "på", "är", "av", "för",
                "med", "inte", "till", "har", "om", "ett", "men", "vi", "du",
                "han", "hon", "den", "de", "här", "vad", "från", "när", "då",
                "sig", "ut", "utan", "över", "under", "igen", "mycket", "alla",
                "skulle", "kunna", "denna", "eller", "så", "ju", "än",
            ),
            "vi" to setOf(
                "và", "của", "là", "không", "có", "các", "được", "cho", "này",
                "với", "người", "về", "những", "thì", "bị", "một", "để",
                "khi", "đã", "cũng", "ở", "vì", "nhưng", "mà", "trong", "tôi",
                "bạn", "anh", "chúng", "nó", "như", "nếu", "ra", "lại", "này",
            ),
        ).map { (code, stopwords) -> LatinRule(code, language(code), stopwords) }

        /** Characteristic diacritics per language code (for confidence boosting). */
        val diacriticsByLanguage: Map<String, Set<Char>> = mapOf(
            "fr" to setOf('é', 'è', 'ê', 'ç'),
            "es" to setOf('é', 'è', 'ê', 'ç', 'ñ'),
            "de" to setOf('ü', 'ö', 'ß'),
            "pl" to setOf('ą', 'ę', 'ł', 'ż'),
            "tr" to setOf('ı', 'ş', 'ğ'),
        )

        const val DIACRITIC_STEP = 0.05f
        const val DIACRITIC_CAP = 0.15f

        /** code -> bonus in [0, DIACRITIC_CAP] based on distinct diacritics found. */
        fun diacriticBonuses(lowerText: String): Map<String, Float> {
            val present = mutableSetOf<Char>()
            for (c in lowerText) {
                if (c in allDiacritics) present.add(c)
            }
            if (present.isEmpty()) return emptyMap()
            val result = mutableMapOf<String, Float>()
            for ((code, chars) in diacriticsByLanguage) {
                val hits = present.count { it in chars }
                if (hits > 0) {
                    result[code] = (hits * DIACRITIC_STEP).coerceAtMost(DIACRITIC_CAP)
                }
            }
            return result
        }

        val allDiacritics: Set<Char> = diacriticsByLanguage.values.flatten().toSet()
    }
}
