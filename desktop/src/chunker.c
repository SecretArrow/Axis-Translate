/* chunker.c — paragraph/sentence chunking + heuristic language detection. */
#include "chunker.h"
#include "langs.h"

#include <stdlib.h>
#include <string.h>

/* Long inputs are split so each prompt stays comfortably inside the default
 * 2048-token context (~4 chars/token) while preserving paragraphs. */
#define AXIS_TARGET_CHUNK 1200
#define AXIS_HARD_CHUNK 1800

static int is_space(char c)
{
    return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\v';
}

/* Trims leading/trailing whitespace in-place on a span. */
static void span_trim(const char *base, size_t *start, size_t *end)
{
    while (*start < *end && is_space(base[*start])) (*start)++;
    while (*end > *start && is_space(base[*end - 1])) (*end)--;
}

static int codepoint_is_cjk_end(const char *p)
{
    /* U+3002 (。) E3 80 82 | U+FF01 (！) EF BC 81 | U+FF1F (？) EF BC 9F */
    unsigned char b0 = (unsigned char)p[0];
    unsigned char b1 = (unsigned char)p[1];
    unsigned char b2 = (unsigned char)p[2];
    if (b0 == 0xE3 && b1 == 0x80 && b2 == 0x82) return 1;
    if (b0 == 0xEF && b1 == 0xBC && (b2 == 0x81 || b2 == 0x9F)) return 1;
    return 0;
}

int axis_chunk_text(const char *text, size_t len, axis_span *out, int max_chunks)
{
    if (!text || len == 0 || !out || max_chunks <= 0) return 0;

    int count = 0;
    size_t para_start = 0;
    size_t i = 0;

    while (i <= len && count < max_chunks) {
        int at_end = (i == len);
        int boundary = 0;
        if (!at_end && text[i] == '\n') {
            /* paragraph boundary = blank line */
            if (i + 1 < len && text[i + 1] == '\n') boundary = 1;
            else if (i + 1 == len) boundary = 1; /* trailing single \n ends final para */
        }
        if (at_end || boundary) {
            size_t s = para_start, e = i;
            span_trim(text, &s, &e);
            if (e > s) {
                size_t plen = e - s;
                if (plen <= AXIS_TARGET_CHUNK) {
                    out[count].ptr = text + s;
                    out[count].len = plen;
                    count++;
                } else {
                    /* split the paragraph at sentence ends */
                    size_t sent_start = s;
                    size_t j = s;
                    while (j < e && count < max_chunks) {
                        size_t next_cut = e; /* default: rest of paragraph */
                        for (size_t k = j; k < e; k++) {
                            if (k + 3 <= e && codepoint_is_cjk_end(text + k)) {
                                next_cut = k + 3;
                                break;
                            }
                            if (text[k] == '.' || text[k] == '!' || text[k] == '?') {
                                /* include following quote/paren chars */
                                size_t m = k + 1;
                                while (m < e && (text[m] == '"' || text[m] == '\'' ||
                                                  text[m] == ')' || text[m] == ']')) m++;
                                next_cut = m;
                                break;
                            }
                            if (k - j >= AXIS_HARD_CHUNK) {
                                /* hard split at last space before the limit */
                                size_t h = k;
                                while (h > j + 1 && !is_space(text[h])) h--;
                                next_cut = (h > j + 1) ? h + 1 : k + 1;
                                break;
                            }
                        }
                        if (next_cut <= j) next_cut = j + 1; /* safety */
                        size_t ss = j, se = next_cut;
                        span_trim(text, &ss, &se);
                        if (se > ss) {
                            if (se - ss > AXIS_HARD_CHUNK) {
                                /* still too long (no break points) — hard split */
                                size_t pos = ss;
                                while (pos + AXIS_HARD_CHUNK < se && count < max_chunks) {
                                    size_t cut = pos + AXIS_TARGET_CHUNK;
                                    while (cut < se && !is_space(text[cut])) cut++;
                                    if (cut >= se || cut - pos < AXIS_TARGET_CHUNK / 2) cut = pos + AXIS_TARGET_CHUNK;
                                    out[count].ptr = text + pos;
                                    out[count].len = cut - pos;
                                    count++;
                                    pos = cut;
                                    while (pos < se && is_space(text[pos])) pos++;
                                }
                                j = pos;
                                sent_start = pos;
                                continue;
                            }
                            out[count].ptr = text + ss;
                            out[count].len = se - ss;
                            count++;
                        }
                        j = next_cut;
                        while (j < e && is_space(text[j])) j++;
                        sent_start = j;
                    }
                    (void)sent_start;
                }
            }
            para_start = i + 1;
        }
        if (at_end) break;
        i++;
    }

    /* normalize newlines inside chunks? kept as-is: the prompt builder
     * receives them verbatim, matching the Android pipeline. */
    return count;
}

/* ------------------------------------------------ language detection ----- */

typedef struct { const char *code; const char *word; } stopword;

/* Compact stopword table covering the manifest's top languages. */
static const stopword kStop[] = {
    { "id", " yang " }, { "id", " dan " }, { "id", " di " }, { "id", " ini " }, { "id", " tidak " },
    { "en", " the " }, { "en", " and " }, { "en", " is " }, { "en", " of " }, { "en", " to " },
    { "es", " el " }, { "es", " la " }, { "es", " que " }, { "es", " de " }, { "es", " los " },
    { "fr", " le " }, { "fr", " la " }, { "fr", " et " }, { "fr", " les " }, { "fr", " des " },
    { "de", " der " }, { "de", " die " }, { "de", " und " }, { "de", " das " }, { "de", " den " },
    { "it", " il " }, { "it", " la " }, { "it", " che " }, { "it", " di " }, { "it", " per " },
    { "pt", " que " }, { "pt", " não " }, { "pt", " de " }, { "pt", " para " }, { "pt", " com " },
    { "nl", " de " }, { "nl", " het " }, { "nl", " een " }, { "nl", " van " }, { "nl", " en " },
    { "pl", " nie " }, { "pl", " się " }, { "pl", " jest " }, { "pl", " tego " }, { "pl", " na " },
    { "tr", " bir " }, { "tr", " ve " }, { "tr", " bu " }, { "tr", " için " }, { "tr", " ile " },
    { "vi", " của " }, { "vi", " và " }, { "vi", " là " }, { "vi", " không " }, { "vi", " có " },
    { "ms", " yang " }, { "ms", " dan " }, { "ms", " tidak " }, { "ms", " dengan " }, { "ms", " ini " },
};

int axis_detect_language(const char *text, size_t len)
{
    if (!text || len == 0) return AXIS_LANG_AUTO;

    /* 1) Script detection wins outright (CJK scripts are unambiguous). */
    size_t pos = 0;
    int cjk_ja = 0, cjk_zh = 0, hangul = 0, cyrillic = 0, arabic = 0, devanagari = 0, thai = 0;
    while (pos < len) {
        unsigned char b = (unsigned char)text[pos];
        if (b < 0x80) { pos++; continue; }
        if (pos + 2 < len + 1) {
            unsigned char b1 = (unsigned char)(pos + 1 < len ? text[pos + 1] : 0);
            unsigned char b2 = (unsigned char)(pos + 2 < len ? text[pos + 2] : 0);
            /* Kana U+3040–30FF: E3 81/82/83... */
            if (b == 0xE3 && (b1 == 0x81 || b1 == 0x82 || (b1 == 0x83 && b2 < 0xA0))) { cjk_ja++; pos += 3; continue; }
            /* CJK ideographs U+4E00–9FFF: E4–E9 */
            if (b >= 0xE4 && b <= 0xE9) { cjk_zh++; pos += 3; continue; }
            /* Hangul U+AC00–D7AF: EA B0–D7 */
            if (b == 0xEA && b1 >= 0xB0) { hangul++; pos += 3; continue; }
            /* Cyrillic U+0400–04FF: D0/D1 */
            if (b == 0xD0 || b == 0xD1) { cyrillic++; pos += 2; continue; }
            /* Arabic U+0600–06FF: D8/D9 */
            if (b == 0xD8 || b == 0xD9) { arabic++; pos += 2; continue; }
            /* Devanagari U+0900–097F: E0 A4/A5 */
            if (b == 0xE0 && (b1 == 0xA4 || b1 == 0xA5)) { devanagari++; pos += 3; continue; }
            /* Thai U+0E00–0E7F: E0 B8/B9 */
            if (b == 0xE0 && (b1 == 0xB8 || b1 == 0xB9)) { thai++; pos += 3; continue; }
        }
        /* skip unknown sequence conservatively */
        pos += (b & 0x80) ? ((b & 0xE0) == 0xC0 ? 2 : ((b & 0xF0) == 0xE0 ? 3 : ((b & 0xF8) == 0xF0 ? 4 : 1))) : 1;
    }

    size_t total_cjk = (size_t)(cjk_ja + cjk_zh);
    if (hangul > 0 && (size_t)hangul > total_cjk) return axis_lang_index("ko");
    if (total_cjk > 0) return (cjk_ja >= cjk_zh && cjk_ja > 0) ? axis_lang_index("ja")
                                                                : axis_lang_index("zh");
    if (cyrillic > 0) return axis_lang_index("ru");
    if (arabic > 0) return axis_lang_index("ar");
    if (devanagari > 0) return axis_lang_index("hi");
    if (thai > 0) return axis_lang_index("th");

    /* 2) Latin-script languages: stopword frequency scoring. */
    char *hay = (char *)malloc(len + 3);
    if (!hay) return AXIS_LANG_AUTO;
    hay[0] = ' ';
    memcpy(hay + 1, text, len);
    hay[len + 1] = ' ';
    hay[len + 2] = '\0';
    /* lowercase */
    for (size_t k = 0; k < len + 2; k++) {
        if (hay[k] >= 'A' && hay[k] <= 'Z') hay[k] = (char)(hay[k] - 'A' + 'a');
    }

    int scores[32] = {0};
    for (size_t s = 0; s < sizeof(kStop) / sizeof(kStop[0]); s++) {
        const char *w = kStop[s].word;
        size_t wlen = strlen(w);
        size_t hits = 0;
        for (size_t p = 0; p + wlen <= len + 2; p++) {
            if (memcmp(hay + p, w, wlen) == 0) hits++;
        }
        int li = axis_lang_index(kStop[s].code);
        if (li >= 0 && li < 32) scores[li] += (int)hits;
    }
    free(hay);

    int best = -1, best_score = 0, second = 0;
    for (int li = 0; li < 32; li++) {
        if (scores[li] > best_score) {
            second = best_score;
            best_score = scores[li];
            best = li;
        } else if (scores[li] > second) {
            second = scores[li];
        }
    }
    if (best < 0 || best_score < 2 || best_score == second) return AXIS_LANG_AUTO;
    return best;
}
