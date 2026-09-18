/* chunker.h — split long input into model-friendly chunks.
 *
 * Mirrors the Android pipeline: paragraphs are kept whole when possible;
 * oversized paragraphs are split at sentence boundaries; runaway sentences
 * are hard-split. Chunk order is preserved. */
#ifndef AXIS_CHUNKER_H
#define AXIS_CHUNKER_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AXIS_CHUNK_MAX 128

typedef struct axis_span {
    const char *ptr;
    size_t len;
} axis_span;

/* Splits text (UTF-8, any newlines) into up to AXIS_CHUNK_MAX chunks.
 * Returns the chunk count (>= 1 for non-empty input, 0 for blank input). */
int axis_chunk_text(const char *text, size_t len, axis_span *out, int max_chunks);

/* Heuristic language detection — a tiny script/stopword scorer good enough
 * for the "Auto" source selection. Returns a language index into the langs
 * catalog, or AXIS_LANG_AUTO when undecided. */
int axis_detect_language(const char *text, size_t len);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_CHUNKER_H */
