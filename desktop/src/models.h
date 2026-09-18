/* models.h — model lifecycle manager (install / import / export / remove).
 *
 * Layout on disk (mirrors the Android app):
 *   <models>/<manifest-entry-id>/<file>   downloaded models
 *   <models>/imported/<file>              user-imported GGUF files
 *
 * All functions are blocking and meant to run on the worker thread; they
 * publish progress through a callback. */
#ifndef AXIS_MODELS_H
#define AXIS_MODELS_H

#include "manifest.h"

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct axis_installed_model {
    char id[64];           /* manifest id, or "imported-<sha8>" */
    char display_name[96];
    char file[128];
    char path[1024];
    uint64_t size;
    char sha256[65];       /* computed at install/import; "" when unknown */
    int ctx_len;
} axis_installed_model;

typedef enum {
    AXIS_MOP_DOWNLOAD = 0,
    AXIS_MOP_VERIFY = 1,
    AXIS_MOP_INSTALL = 2,
    AXIS_MOP_IMPORT = 3,
    AXIS_MOP_EXPORT = 4,
} axis_model_op;

typedef void (*axis_models_progress)(void *ud, axis_model_op op,
                                     uint64_t done, uint64_t total);

/* Scans the models dir for an installed model (manifest entries in priority
 * order, then any GGUF in the imported directory). Returns 1 when found,
 * 0 when none, -1 err. */
int axis_models_scan(const axis_manifest *m, const char *models_dir,
                     axis_installed_model *out, char *err, size_t errsz);

/* Download + SHA-256 verify + install a manifest entry. */
int axis_models_install(const axis_manifest_entry *e, const char *models_dir,
                        axis_models_progress cb, void *ud,
                        axis_installed_model *out, char *err, size_t errsz);

/* Import a local GGUF (magic-checked) into the models dir. */
int axis_models_import(const char *src_path, const char *models_dir,
                       axis_models_progress cb, void *ud,
                       axis_installed_model *out, char *err, size_t errsz);

/* Export the installed model file into dest_dir (keeps its file name,
 * unless filename_override is given). */
int axis_models_export(const axis_installed_model *inst, const char *dest_dir,
                       const char *filename_override,
                       axis_models_progress cb, void *ud,
                       char *err, size_t errsz);

/* Recompute the SHA-256 of the installed file. Returns 1 match / 0 mismatch
 * (mismatch still fills computed_hex). */
int axis_models_verify(const axis_installed_model *inst, const axis_manifest *m,
                       char *computed_hex /* [65] or NULL */,
                       char *err, size_t errsz);

/* Removes the installed model directory (or imported file). */
int axis_models_remove(const axis_installed_model *inst, char *err, size_t errsz);

/* "12.3 MB" / "1.2 GB" formatting for the UI. */
void axis_models_format_bytes(uint64_t bytes, char *out, size_t n);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_MODELS_H */
