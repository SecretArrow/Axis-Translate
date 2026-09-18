/* str.h — growable string builder + UTF-8 helpers.
 *
 * axis_str owns a heap buffer, always NUL terminated, tracks length. It is
 * the workhorse for prompt building, streamed generation output, history
 * serialization, and path joining. */
#ifndef AXIS_STR_H
#define AXIS_STR_H

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct axis_str {
    char *data;
    size_t len;
    size_t cap;   /* capacity includes the NUL byte */
    int oom;
} axis_str;

void axis_str_init(axis_str *s);
void axis_str_init_cap(axis_str *s, size_t cap);
void axis_str_free(axis_str *s);
void axis_str_reserve(axis_str *s, size_t extra);
void axis_str_clear(axis_str *s);

void axis_str_append(axis_str *s, const char *text);
void axis_str_append_n(axis_str *s, const char *text, size_t n);
void axis_str_append_c(axis_str *s, char c);
void axis_str_appendf(axis_str *s, const char *fmt, ...);

/* Path joining: appends "/" + part (or "\\" on Windows). */
void axis_str_path_join(axis_str *s, const char *part);

/* UTF-8 helpers ---------------------------------------------------------- */

/* Number of bytes in the UTF-8 sequence starting with byte b (1..4, or 1 for
 * invalid/continuation bytes). */
int axis_utf8_seq_len(unsigned char b);

/* Decodes one codepoint at *pos; advances *pos. Returns 0xFFFD on invalid
 * sequences. Writes nothing when text is NULL. */
uint32_t axis_utf8_decode(const char *text, size_t *pos);

/* Encodes codepoint cp at the end of s. */
void axis_utf8_encode(axis_str *s, uint32_t cp);

/* Truncate the string so it stays valid UTF-8 (no trailing partial seq). */
void axis_utf8_truncate(axis_str *s, size_t max_bytes);

/* Case-insensitive ASCII compare (ASCII only — fine for codes like "en"). */
int axis_str_ieq(const char *a, const char *b);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_STR_H */
