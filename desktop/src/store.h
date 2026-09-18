/* store.h — settings + history persistence.
 *
 * Config file:  <config>/settings.conf   (key=value lines)
 * History:      <config>/history.log     (one JSON object per line)
 *
 * The store is intentionally tiny: fixed-size fields, simple parsing, no
 * dynamic schema. */
#ifndef AXIS_STORE_H
#define AXIS_STORE_H

#include "prompt.h"

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AXIS_HISTORY_MAX 200
#define AXIS_HISTORY_TEXT_MAX 512

typedef struct axis_config {
    int dark_theme;        /* 1 = dark (default) */
    int threads;           /* 1..16 (default: 4) */
    int context_length;    /* 256..16384 (default: 2048) */
    float temperature;     /* 0..1 (default: 0.1) */
    int max_output_tokens; /* 64..4096 (default: 768) */
    axis_style_t style;    /* default: STANDARD */
    int source_lang;       /* langs index, AXIS_LANG_AUTO allowed */
    int target_lang;       /* langs index */
} axis_config;

void axis_config_defaults(axis_config *cfg);

typedef struct axis_history_item {
    uint64_t timestamp;
    char source_lang[8];
    char target_lang[8];
    char input[AXIS_HISTORY_TEXT_MAX];
    char output[AXIS_HISTORY_TEXT_MAX];
} axis_history_item;

typedef struct axis_history {
    axis_history_item items[AXIS_HISTORY_MAX];
    int count;
} axis_history;

/* Loads settings (missing file -> defaults). Returns 0 on success. */
int axis_store_load_config(const char *config_dir, axis_config *cfg);

/* Saves settings atomically (write + rename). */
int axis_store_save_config(const char *config_dir, const axis_config *cfg);

/* Loads history (most recent last). Missing file -> empty. */
int axis_store_load_history(const char *config_dir, axis_history *h);

/* Appends one entry (drops the oldest when full) and persists. */
int axis_store_add_history(const char *config_dir, axis_history *h,
                           const char *src_code, const char *tgt_code,
                           const char *input, const char *output);

/* Clears memory + deletes the file. */
int axis_store_clear_history(const char *config_dir, axis_history *h);

/* JSON string escaping for one history line (into a fixed buffer). */
void axis_store_json_escape(const char *in, char *out, size_t outn);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_STORE_H */
