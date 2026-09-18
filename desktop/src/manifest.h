/* manifest.h — model manifest parsing (same JSON schema as the Android
 * app's model_manifest.json, embedded into the binary). */
#ifndef AXIS_MANIFEST_H
#define AXIS_MANIFEST_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AXIS_MANIFEST_MAX_ENTRIES 12
#define AXIS_MANIFEST_MAX_LANGS 48

typedef struct axis_manifest_lang {
    char code[8];
    char name[48];
} axis_manifest_lang;

typedef struct axis_manifest_entry {
    char id[64];
    char display_name[96];
    char description[256];
    char quantization[32];
    char file[128];
    char url[384];
    char sha256[65];
    uint64_t size_bytes;
    int context_length;
    char runtime[32];
    char license[64];
    int is_default;
    axis_manifest_lang langs[AXIS_MANIFEST_MAX_LANGS];
    int lang_count;
} axis_manifest_entry;

typedef struct axis_manifest {
    int schema_version;
    int count;
    axis_manifest_entry entries[AXIS_MANIFEST_MAX_ENTRIES];
} axis_manifest;

/* Parses the manifest JSON. Returns 0 on success; -1 on parse failure.
 * Unknown fields are ignored; missing optional fields fall back to
 * defaults (contextLength 2048, runtime "llama.cpp"). */
int axis_manifest_parse(axis_manifest *m, const char *json, size_t len);

/* Loads the embedded manifest. Returns 0 on success. */
int axis_manifest_load_embedded(axis_manifest *m);

/* Finds the default entry (falls back to the first). NULL when empty. */
const axis_manifest_entry *axis_manifest_default(const axis_manifest *m);

const axis_manifest_entry *axis_manifest_by_id(const axis_manifest *m, const char *id);

/* "Not yet published" placeholder checksum (all-zero) — verification is
 * skipped for those entries, mirroring the Android repository. */
int axis_manifest_sha_known(const char *sha256);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_MANIFEST_H */
