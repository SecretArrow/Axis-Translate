/* str.c — growable string builder + UTF-8 helpers. */
#include "str.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void axis_str_grow(axis_str *s, size_t extra)
{
    if (s->oom) return;
    size_t need = s->len + extra + 1;
    if (need <= s->cap) return;
    size_t ncap = s->cap ? s->cap : 64;
    while (ncap < need) ncap *= 2;
    char *nd = (char *)realloc(s->data, ncap);
    if (!nd) { s->oom = 1; return; }
    s->data = nd;
    s->cap = ncap;
}

void axis_str_init(axis_str *s)
{
    s->data = NULL;
    s->len = 0;
    s->cap = 0;
    s->oom = 0;
}

void axis_str_init_cap(axis_str *s, size_t cap)
{
    axis_str_init(s);
    axis_str_grow(s, cap + 1);
    if (s->data) s->data[0] = '\0';
}

void axis_str_free(axis_str *s)
{
    free(s->data);
    s->data = NULL;
    s->len = 0;
    s->cap = 0;
    s->oom = 0;
}

void axis_str_reserve(axis_str *s, size_t extra)
{
    axis_str_grow(s, extra);
}

void axis_str_clear(axis_str *s)
{
    s->len = 0;
    if (s->data) s->data[0] = '\0';
}

void axis_str_append_n(axis_str *s, const char *text, size_t n)
{
    if (!text || n == 0) {
        if (s->data == NULL) axis_str_grow(s, 1);
        if (s->data && s->len == 0) s->data[0] = '\0';
        return;
    }
    axis_str_grow(s, n);
    if (s->oom) return;
    memcpy(s->data + s->len, text, n);
    s->len += n;
    s->data[s->len] = '\0';
}

void axis_str_append(axis_str *s, const char *text)
{
    if (!text) return;
    axis_str_append_n(s, text, strlen(text));
}

void axis_str_append_c(axis_str *s, char c)
{
    axis_str_append_n(s, &c, 1);
}

void axis_str_appendf(axis_str *s, const char *fmt, ...)
{
    if (s->oom) return;
    va_list ap;
    va_start(ap, fmt);
    va_list ap2;
    va_copy(ap2, ap);
    int need = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    if (need < 0) { va_end(ap2); return; }
    size_t extra = (size_t)need;
    axis_str_grow(s, extra);
    if (s->oom) { va_end(ap2); return; }
    vsnprintf(s->data + s->len, extra + 1, fmt, ap2);
    va_end(ap2);
    s->len += extra;
}

void axis_str_path_join(axis_str *s, const char *part)
{
    if (s->len > 0) {
        char last = s->data[s->len - 1];
#ifdef _WIN32
        if (last != '\\' && last != '/') axis_str_append_c(s, '\\');
#else
        if (last != '/') axis_str_append_c(s, '/');
#endif
    }
    axis_str_append(s, part);
}

/* UTF-8 helpers ---------------------------------------------------------- */

int axis_utf8_seq_len(unsigned char b)
{
    if (b < 0x80) return 1;
    if ((b & 0xE0) == 0xC0) return 2;
    if ((b & 0xF0) == 0xE0) return 3;
    if ((b & 0xF8) == 0xF0) return 4;
    return 1; /* invalid / continuation byte — treat as single */
}

uint32_t axis_utf8_decode(const char *text, size_t *pos)
{
    if (!text || !pos) return 0xFFFD;
    size_t p = *pos;
    unsigned char b0 = (unsigned char)text[p];
    if (b0 == '\0') return 0;

    int n = axis_utf8_seq_len(b0);
    uint32_t cp = 0;
    if (n == 1) {
        cp = b0;
    } else {
        cp = (uint32_t)(b0 & (0xFFu >> (n + 1)));
        for (int i = 1; i < n; i++) {
            unsigned char bi = (unsigned char)text[p + (size_t)i];
            if ((bi & 0xC0) != 0x80) { cp = 0xFFFD; n = 1; break; }
            cp = (cp << 6) | (uint32_t)(bi & 0x3Fu);
        }
    }
    *pos = p + (size_t)n;
    return cp;
}

void axis_utf8_encode(axis_str *s, uint32_t cp)
{
    if (cp < 0x80) {
        axis_str_append_c(s, (char)cp);
    } else if (cp < 0x800) {
        char buf[2];
        buf[0] = (char)(0xC0 | (cp >> 6));
        buf[1] = (char)(0x80 | (cp & 0x3Fu));
        axis_str_append_n(s, buf, 2);
    } else if (cp < 0x10000) {
        char buf[3];
        buf[0] = (char)(0xE0 | (cp >> 12));
        buf[1] = (char)(0x80 | ((cp >> 6) & 0x3Fu));
        buf[2] = (char)(0x80 | (cp & 0x3Fu));
        axis_str_append_n(s, buf, 3);
    } else {
        char buf[4];
        buf[0] = (char)(0xF0 | (cp >> 18));
        buf[1] = (char)(0x80 | ((cp >> 12) & 0x3Fu));
        buf[2] = (char)(0x80 | ((cp >> 6) & 0x3Fu));
        buf[3] = (char)(0x80 | (cp & 0x3Fu));
        axis_str_append_n(s, buf, 4);
    }
}

void axis_utf8_truncate(axis_str *s, size_t max_bytes)
{
    if (s->len <= max_bytes) return;
    size_t cut = max_bytes;
    /* walk back up to 3 bytes to avoid splitting a UTF-8 sequence */
    while (cut > 0 && cut > max_bytes - 4 &&
           ((unsigned char)s->data[cut] & 0xC0) == 0x80) {
        cut--;
    }
    if (cut > max_bytes) cut = max_bytes; /* defensive */
    s->len = cut;
    s->data[cut] = '\0';
}

int axis_str_ieq(const char *a, const char *b)
{
    if (!a || !b) return a == b;
    while (*a && *b) {
        if (tolower((unsigned char)*a) != tolower((unsigned char)*b)) return 0;
        a++;
        b++;
    }
    return *a == *b;
}
