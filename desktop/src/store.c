/* store.c — settings + history persistence implementation.
 *
 * strtok_r is POSIX; MSVC provides the identical strtok_s. */
#define _POSIX_C_SOURCE 200809L

#include "store.h"
#include "fs.h"
#include "langs.h"
#include "str.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#define strtok_r strtok_s
#endif

#define JSMN_STATIC
#include "../third_party/jsmn/jsmn.h"

#define SETTINGS_FILE "settings.conf"
#define HISTORY_FILE "history.log"

void axis_config_defaults(axis_config *cfg)
{
    memset(cfg, 0, sizeof(*cfg));
    cfg->dark_theme = 1;
    cfg->threads = 4;
    cfg->context_length = 2048;
    cfg->temperature = 0.1f;
    cfg->max_output_tokens = 768;
    cfg->style = AXIS_STYLE_STANDARD;
    cfg->source_lang = AXIS_LANG_AUTO;
    cfg->target_lang = axis_lang_index("id");
    if (cfg->target_lang < 0) cfg->target_lang = 0;
}

/* ------------------------------------------------------------- settings --- */

int axis_store_load_config(const char *config_dir, axis_config *cfg)
{
    axis_config_defaults(cfg);
    if (!config_dir) return -1;

    axis_str path;
    axis_str_init_cap(&path, 512);
    axis_str_append(&path, config_dir);
    axis_str_path_join(&path, SETTINGS_FILE);

    char *text = axis_fs_read_all(path.data, NULL);
    axis_str_free(&path);
    if (!text) return 0; /* fresh install */

    char *save = NULL;
    for (char *line = strtok_r(text, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = '\0';
        const char *key = line;
        const char *val = eq + 1;
        if (strcmp(key, "dark_theme") == 0) cfg->dark_theme = atoi(val) ? 1 : 0;
        else if (strcmp(key, "threads") == 0) cfg->threads = atoi(val);
        else if (strcmp(key, "context_length") == 0) cfg->context_length = atoi(val);
        else if (strcmp(key, "temperature") == 0) cfg->temperature = (float)atof(val);
        else if (strcmp(key, "max_output_tokens") == 0) cfg->max_output_tokens = atoi(val);
        else if (strcmp(key, "style") == 0) cfg->style = (axis_style_t)atoi(val);
        else if (strcmp(key, "source_lang") == 0) cfg->source_lang = axis_lang_index(val);
        else if (strcmp(key, "target_lang") == 0) cfg->target_lang = axis_lang_index(val);
    }
    free(text);

    /* sanitize */
    if (cfg->threads < 1) cfg->threads = 1;
    if (cfg->threads > 16) cfg->threads = 16;
    if (cfg->context_length < 256) cfg->context_length = 256;
    if (cfg->context_length > 16384) cfg->context_length = 16384;
    if (cfg->temperature < 0.0f) cfg->temperature = 0.0f;
    if (cfg->temperature > 1.0f) cfg->temperature = 1.0f;
    if (cfg->max_output_tokens < 64) cfg->max_output_tokens = 64;
    if (cfg->max_output_tokens > 4096) cfg->max_output_tokens = 4096;
    if (cfg->style < 0 || cfg->style > AXIS_STYLE_CASUAL) cfg->style = AXIS_STYLE_STANDARD;
    if (cfg->target_lang < 0) cfg->target_lang = 0;
    return 0;
}

int axis_store_save_config(const char *config_dir, const axis_config *cfg)
{
    if (!config_dir || !cfg) return -1;

    axis_str path, tmp;
    axis_str_init_cap(&path, 512);
    axis_str_init_cap(&tmp, 512);
    axis_str_append(&path, config_dir);
    axis_str_path_join(&path, SETTINGS_FILE);
    axis_str_append(&tmp, path.data);
    axis_str_append(&tmp, ".tmp");

    int rc = -1;
    FILE *f = fopen(tmp.data, "wb");
    if (f) {
        fprintf(f, "dark_theme=%d\n", cfg->dark_theme ? 1 : 0);
        fprintf(f, "threads=%d\n", cfg->threads);
        fprintf(f, "context_length=%d\n", cfg->context_length);
        fprintf(f, "temperature=%.2f\n", cfg->temperature);
        fprintf(f, "max_output_tokens=%d\n", cfg->max_output_tokens);
        fprintf(f, "style=%d\n", (int)cfg->style);
        fprintf(f, "source_lang=%s\n", axis_lang_code(cfg->source_lang));
        fprintf(f, "target_lang=%s\n", axis_lang_code(cfg->target_lang));
        if (fflush(f) == 0 && fclose(f) == 0) {
            axis_fs_remove_file(path.data);
            if (rename(tmp.data, path.data) == 0) rc = 0;
        } else {
            fclose(f);
        }
    }
    axis_str_free(&path);
    axis_str_free(&tmp);
    return rc;
}

/* -------------------------------------------------------------- history --- */

void axis_store_json_escape(const char *in, char *out, size_t outn)
{
    if (!out || outn == 0) return;
    size_t o = 0;
    for (const char *p = in ? in : ""; *p && o + 7 < outn; p++) {
        unsigned char c = (unsigned char)*p;
        if (c == '"' || c == '\\') {
            out[o++] = '\\';
            out[o++] = (char)c;
        } else if (c == '\n') {
            out[o++] = '\\'; out[o++] = 'n';
        } else if (c == '\r') {
            out[o++] = '\\'; out[o++] = 'r';
        } else if (c == '\t') {
            out[o++] = '\\'; out[o++] = 't';
        } else if (c < 0x20) {
            snprintf(out + o, outn - o, "\\u%04x", c);
            o += 6;
        } else {
            out[o++] = (char)c;
        }
    }
    out[o] = '\0';
}

static void json_unescape(const char *in, char *out, size_t outn)
{
    if (!out || outn == 0) return;
    size_t o = 0;
    for (const char *p = in ? in : ""; *p && o + 1 < outn; p++) {
        if (*p == '\\' && p[1]) {
            p++;
            switch (*p) {
                case 'n': out[o++] = '\n'; break;
                case 'r': out[o++] = '\r'; break;
                case 't': out[o++] = '\t'; break;
                case '"': out[o++] = '"'; break;
                case '\\': out[o++] = '\\'; break;
                case 'u': {
                    if (p[1] && p[2] && p[3] && p[4]) {
                        unsigned v = 0;
                        for (int i = 1; i <= 4; i++) {
                            char h = p[i];
                            v <<= 4;
                            if (h >= '0' && h <= '9') v |= (unsigned)(h - '0');
                            else if (h >= 'a' && h <= 'f') v |= (unsigned)(h - 'a' + 10);
                            else if (h >= 'A' && h <= 'F') v |= (unsigned)(h - 'A' + 10);
                        }
                        p += 4;
                        if (v < 0x80) {
                            out[o++] = (char)v;
                        } else if (v < 0x800 && o + 2 < outn) {
                            out[o++] = (char)(0xC0 | (v >> 6));
                            out[o++] = (char)(0x80 | (v & 0x3F));
                        } else if (o + 3 < outn) {
                            out[o++] = (char)(0xE0 | (v >> 12));
                            out[o++] = (char)(0x80 | ((v >> 6) & 0x3F));
                            out[o++] = (char)(0x80 | (v & 0x3F));
                        }
                    }
                    break;
                }
                default: out[o++] = *p; break;
            }
        } else {
            out[o++] = *p;
        }
    }
    out[o] = '\0';
}

static void tok_str(char *dst, size_t cap, const char *line, jsmntok_t *t)
{
    if (!t || t->type != JSMN_STRING || cap == 0) { if (cap) dst[0] = '\0'; return; }
    size_t n = (size_t)(t->end - t->start);
    if (n >= 512) n = 511;
    char buf[512];
    memcpy(buf, line + t->start, n);
    buf[n] = '\0';
    json_unescape(buf, dst, cap);
}

static void parse_history_line(axis_history_item *item, const char *line, size_t len)
{
    jsmn_parser p;
    jsmntok_t toks[24];
    jsmn_init(&p);
    int count = jsmn_parse(&p, line, len, toks, 24);
    if (count < 1 || toks[0].type != JSMN_OBJECT) return;

    for (int i = 1; i + 1 < count; i++) {
        jsmntok_t *k = &toks[i];
        jsmntok_t *v = &toks[i + 1];
        if (k->type != JSMN_STRING) continue;
        size_t klen = (size_t)(k->end - k->start);
        if (klen == 1 && line[k->start] == 't' && v->type == JSMN_PRIMITIVE) {
            char buf[32];
            size_t n = (size_t)(v->end - v->start);
            if (n >= sizeof(buf)) n = sizeof(buf) - 1;
            memcpy(buf, line + v->start, n);
            buf[n] = '\0';
            item->timestamp = strtoull(buf, NULL, 10);
        } else if (klen == 3 && memcmp(line + k->start, "src", 3) == 0) {
            tok_str(item->source_lang, sizeof(item->source_lang), line, v);
        } else if (klen == 3 && memcmp(line + k->start, "tgt", 3) == 0) {
            tok_str(item->target_lang, sizeof(item->target_lang), line, v);
        } else if (klen == 2 && memcmp(line + k->start, "in", 2) == 0) {
            tok_str(item->input, sizeof(item->input), line, v);
        } else if (klen == 3 && memcmp(line + k->start, "out", 3) == 0) {
            tok_str(item->output, sizeof(item->output), line, v);
        }
        i = i + 1; /* advance past value (values here are flat) */
    }
}

int axis_store_load_history(const char *config_dir, axis_history *h)
{
    if (!h) return -1;
    memset(h, 0, sizeof(*h));
    if (!config_dir) return 0;

    axis_str path;
    axis_str_init_cap(&path, 512);
    axis_str_append(&path, config_dir);
    axis_str_path_join(&path, HISTORY_FILE);

    char *text = axis_fs_read_all(path.data, NULL);
    axis_str_free(&path);
    if (!text) return 0;

    char *save = NULL;
    for (char *line = strtok_r(text, "\n", &save); line && h->count < AXIS_HISTORY_MAX;
         line = strtok_r(NULL, "\n", &save)) {
        if (line[0] != '{') continue;
        axis_history_item item;
        memset(&item, 0, sizeof(item));
        parse_history_line(&item, line, strlen(line));
        h->items[h->count++] = item;
    }
    free(text);
    return 0;
}

int axis_store_add_history(const char *config_dir, axis_history *h,
                           const char *src_code, const char *tgt_code,
                           const char *input, const char *output)
{
    if (!h) return -1;

    if (h->count >= AXIS_HISTORY_MAX) {
        memmove(&h->items[0], &h->items[1],
                sizeof(axis_history_item) * (AXIS_HISTORY_MAX - 1));
        h->count = AXIS_HISTORY_MAX - 1;
    }

    axis_history_item *item = &h->items[h->count++];
    memset(item, 0, sizeof(*item));
    item->timestamp = (uint64_t)time(NULL);
    snprintf(item->source_lang, sizeof(item->source_lang), "%s", src_code ? src_code : "");
    snprintf(item->target_lang, sizeof(item->target_lang), "%s", tgt_code ? tgt_code : "");

    /* Truncate input/output to valid UTF-8 boundaries before storing. */
    axis_str tmp;
    axis_str_init_cap(&tmp, 256);
    axis_str_append(&tmp, input ? input : "");
    axis_utf8_truncate(&tmp, AXIS_HISTORY_TEXT_MAX - 1);
    memcpy(item->input, tmp.data ? tmp.data : "", tmp.len + 1);
    axis_str_clear(&tmp);
    axis_str_append(&tmp, output ? output : "");
    axis_utf8_truncate(&tmp, AXIS_HISTORY_TEXT_MAX - 1);
    memcpy(item->output, tmp.data ? tmp.data : "", tmp.len + 1);
    axis_str_free(&tmp);

    if (!config_dir) return 0;

    /* append to the file */
    axis_str path;
    axis_str_init_cap(&path, 512);
    axis_str_append(&path, config_dir);
    axis_str_path_join(&path, HISTORY_FILE);

    FILE *f = fopen(path.data, "ab");
    if (f) {
        char ein[AXIS_HISTORY_TEXT_MAX * 2 + 8];
        char eout[AXIS_HISTORY_TEXT_MAX * 2 + 8];
        axis_store_json_escape(item->input, ein, sizeof(ein));
        axis_store_json_escape(item->output, eout, sizeof(eout));
        fprintf(f, "{\"t\":%llu,\"src\":\"%s\",\"tgt\":\"%s\",\"in\":\"%s\",\"out\":\"%s\"}\n",
                (unsigned long long)item->timestamp,
                item->source_lang, item->target_lang, ein, eout);
        fclose(f);
    }
    axis_str_free(&path);
    return 0;
}

int axis_store_clear_history(const char *config_dir, axis_history *h)
{
    if (h) memset(h, 0, sizeof(*h));
    if (!config_dir) return 0;

    axis_str path;
    axis_str_init_cap(&path, 512);
    axis_str_append(&path, config_dir);
    axis_str_path_join(&path, HISTORY_FILE);
    axis_fs_remove_file(path.data);
    axis_str_free(&path);
    return 0;
}
