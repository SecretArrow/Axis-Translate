/* manifest.c — model manifest parsing via jsmn (vendored, MIT). */
#include "manifest.h"
#include "embedded_manifest.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define JSMN_STATIC
#define JSMN_IMPLEMENTATION
#include "../third_party/jsmn/jsmn.h"

#define AXIS_JSMN_TOKENS 2048

/* ------------------------------------------------------------------ utils */

static void copy_str(char *dst, size_t cap, const char *json, jsmntok_t *t)
{
    if (!t || cap == 0) return;
    size_t n = (size_t)(t->end - t->start);
    if (n >= cap) n = cap - 1;
    memcpy(dst, json + t->start, n);
    dst[n] = '\0';
}

static int tok_is_key(jsmntok_t *t, const char *json, const char *key)
{
    size_t klen = strlen(key);
    return t->type == JSMN_STRING && (size_t)(t->end - t->start) == klen &&
           memcmp(json + t->start, key, klen) == 0;
}

static uint64_t parse_u64(const char *json, jsmntok_t *t)
{
    char buf[32];
    size_t n = (size_t)(t->end - t->start);
    if (n >= sizeof(buf)) n = sizeof(buf) - 1;
    memcpy(buf, json + t->start, n);
    buf[n] = '\0';
    return strtoull(buf, NULL, 10);
}

static int parse_int(const char *json, jsmntok_t *t)
{
    return (int)parse_u64(json, t);
}

static int parse_bool(const char *json, jsmntok_t *t)
{
    return (t->end - t->start) >= 4 && memcmp(json + t->start, "true", 4) == 0;
}

/* Returns the index of the first token AFTER the value that starts at idx
 * (handles nested objects/arrays). */
static int skip_value(jsmntok_t *toks, int count, int idx)
{
    if (idx >= count) return count;
    int end = toks[idx].end;
    int i = idx + 1;
    while (i < count && toks[i].start < end) i++;
    return i;
}

/* ---------------------------------------------------------------- parsing */

static void parse_langs(axis_manifest_entry *e, const char *json,
                        jsmntok_t *toks, int count, int arr_idx)
{
    int end = toks[arr_idx].end;
    int i = arr_idx + 1;
    while (i < count && toks[i].start < end) {
        jsmntok_t *obj = &toks[i];
        if (obj->type != JSMN_OBJECT) { i = skip_value(toks, count, i); continue; }

        axis_manifest_lang lang;
        memset(&lang, 0, sizeof(lang));
        int oend = obj->end;
        int j = i + 1;
        while (j < count && toks[j].start < oend) {
            if (toks[j].type != JSMN_STRING) break;
            if (j + 1 >= count) break;
            jsmntok_t *fk = &toks[j];
            jsmntok_t *fv = &toks[j + 1];
            if (tok_is_key(fk, json, "code") && fv->type == JSMN_STRING) {
                copy_str(lang.code, sizeof(lang.code), json, fv);
            } else if (tok_is_key(fk, json, "name") && fv->type == JSMN_STRING) {
                copy_str(lang.name, sizeof(lang.name), json, fv);
            } else if (tok_is_key(fk, json, "nativeName") && fv->type == JSMN_STRING) {
                if (lang.name[0] == '\0')
                    copy_str(lang.name, sizeof(lang.name), json, fv);
            }
            j = skip_value(toks, count, j + 1);
        }
        if (e->lang_count < AXIS_MANIFEST_MAX_LANGS && lang.code[0])
            e->langs[e->lang_count++] = lang;
        i = j;
    }
}

static void parse_entry(axis_manifest_entry *e, const char *json,
                        jsmntok_t *toks, int count, int obj_idx)
{
    memset(e, 0, sizeof(*e));
    e->context_length = 2048;
    snprintf(e->runtime, sizeof(e->runtime), "llama.cpp");

    int end = toks[obj_idx].end;
    int i = obj_idx + 1;
    while (i < count && toks[i].start < end) {
        if (toks[i].type != JSMN_STRING) break;
        if (i + 1 >= count) break;
        jsmntok_t *fk = &toks[i];
        jsmntok_t *fv = &toks[i + 1];

        if (tok_is_key(fk, json, "id") && fv->type == JSMN_STRING) {
            copy_str(e->id, sizeof(e->id), json, fv);
        } else if (tok_is_key(fk, json, "displayName") && fv->type == JSMN_STRING) {
            copy_str(e->display_name, sizeof(e->display_name), json, fv);
        } else if (tok_is_key(fk, json, "description") && fv->type == JSMN_STRING) {
            copy_str(e->description, sizeof(e->description), json, fv);
        } else if (tok_is_key(fk, json, "quantization") && fv->type == JSMN_STRING) {
            copy_str(e->quantization, sizeof(e->quantization), json, fv);
        } else if (tok_is_key(fk, json, "file") && fv->type == JSMN_STRING) {
            copy_str(e->file, sizeof(e->file), json, fv);
        } else if (tok_is_key(fk, json, "url") && fv->type == JSMN_STRING) {
            copy_str(e->url, sizeof(e->url), json, fv);
        } else if (tok_is_key(fk, json, "sha256") && fv->type == JSMN_STRING) {
            copy_str(e->sha256, sizeof(e->sha256), json, fv);
        } else if (tok_is_key(fk, json, "sizeBytes") && fv->type == JSMN_PRIMITIVE) {
            e->size_bytes = parse_u64(json, fv);
        } else if (tok_is_key(fk, json, "contextLength") && fv->type == JSMN_PRIMITIVE) {
            e->context_length = parse_int(json, fv);
        } else if (tok_is_key(fk, json, "runtime") && fv->type == JSMN_STRING) {
            copy_str(e->runtime, sizeof(e->runtime), json, fv);
        } else if (tok_is_key(fk, json, "license") && fv->type == JSMN_STRING) {
            copy_str(e->license, sizeof(e->license), json, fv);
        } else if (tok_is_key(fk, json, "default") && fv->type == JSMN_PRIMITIVE) {
            e->is_default = parse_bool(json, fv);
        } else if (tok_is_key(fk, json, "languages") && fv->type == JSMN_ARRAY) {
            parse_langs(e, json, toks, count, i + 1);
        }
        /* unknown fields are skipped */
        i = skip_value(toks, count, i + 1);
    }
}

int axis_manifest_parse(axis_manifest *m, const char *json, size_t len)
{
    if (!m || !json) return -1;
    memset(m, 0, sizeof(*m));
    m->schema_version = 1;

    jsmn_parser p;
    jsmntok_t toks[AXIS_JSMN_TOKENS];
    jsmn_init(&p);
    int count = jsmn_parse(&p, json, len, toks, (unsigned int)(sizeof(toks) / sizeof(toks[0])));
    if (count < 1 || toks[0].type != JSMN_OBJECT) return -1;

    int end = toks[0].end;
    int i = 1;
    while (i < count && toks[i].start < end) {
        if (toks[i].type != JSMN_STRING) break;
        if (i + 1 >= count) break;
        jsmntok_t *fk = &toks[i];
        jsmntok_t *fv = &toks[i + 1];

        if (tok_is_key(fk, json, "schemaVersion") && fv->type == JSMN_PRIMITIVE) {
            m->schema_version = parse_int(json, fv);
        } else if (tok_is_key(fk, json, "models") && fv->type == JSMN_ARRAY) {
            int aend = fv->end;
            int j = i + 2; /* first token inside the array */
            while (j < count && toks[j].start < aend) {
                if (toks[j].type != JSMN_OBJECT) break;
                if (m->count < AXIS_MANIFEST_MAX_ENTRIES) {
                    parse_entry(&m->entries[m->count], json, toks, count, j);
                    m->count++;
                }
                j = skip_value(toks, count, j);
            }
        }
        i = skip_value(toks, count, i + 1);
    }

    if (m->count == 0) return -1;
    return 0;
}

int axis_manifest_load_embedded(axis_manifest *m)
{
    size_t len = strlen(AXIS_MANIFEST_JSON);
    return axis_manifest_parse(m, AXIS_MANIFEST_JSON, len);
}

const axis_manifest_entry *axis_manifest_default(const axis_manifest *m)
{
    if (!m || m->count == 0) return NULL;
    for (int i = 0; i < m->count; i++) {
        if (m->entries[i].is_default) return &m->entries[i];
    }
    return &m->entries[0];
}

const axis_manifest_entry *axis_manifest_by_id(const axis_manifest *m, const char *id)
{
    if (!m || !id) return NULL;
    for (int i = 0; i < m->count; i++) {
        if (strcmp(m->entries[i].id, id) == 0) return &m->entries[i];
    }
    return NULL;
}

int axis_manifest_sha_known(const char *sha256)
{
    if (!sha256 || sha256[0] == '\0') return 0;
    for (const char *p = sha256; *p; p++) {
        if (*p != '0') return 1;
    }
    return 0; /* all zeros = placeholder */
}
