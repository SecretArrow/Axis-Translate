/* models.c — model lifecycle manager implementation. */
#include "models.h"
#include "download.h"
#include "fs.h"
#include "platform.h"
#include "sha256.h"
#include "str.h"

#include <stdio.h>
#include <string.h>

#define AXIS_IMPORT_DIR "imported"
#define AXIS_PART_SUFFIX ".part"

void axis_models_format_bytes(uint64_t bytes, char *out, size_t n)
{
    if (!out || n == 0) return;
    if (bytes >= (1024ULL * 1024ULL * 1024ULL * 1024ULL)) {
        snprintf(out, n, "%.2f TB", (double)bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0));
    } else if (bytes >= (1024ULL * 1024ULL * 1024ULL)) {
        snprintf(out, n, "%.2f GB", (double)bytes / (1024.0 * 1024.0 * 1024.0));
    } else if (bytes >= (1024ULL * 1024ULL)) {
        snprintf(out, n, "%.1f MB", (double)bytes / (1024.0 * 1024.0));
    } else if (bytes >= 1024ULL) {
        snprintf(out, n, "%.1f KB", (double)bytes / 1024.0);
    } else {
        snprintf(out, n, "%llu B", (unsigned long long)bytes);
    }
}

/* ------------------------------------------------------------- scanning --- */

static int scan_imported(const char *models_dir, axis_installed_model *out)
{
    axis_str dir;
    axis_str_init_cap(&dir, 256);
    axis_str_append(&dir, models_dir);
    axis_str_path_join(&dir, AXIS_IMPORT_DIR);

    int found = 0;
    if (axis_fs_is_dir(dir.data)) {
        axis_dir *d = axis_dir_open(dir.data);
        if (d) {
            axis_dirent e;
            while (axis_dir_next(d, &e) == 0) {
                if (e.is_dir) continue;
                if (!strstr(e.name, ".gguf")) continue;
                axis_str path;
                axis_str_init_cap(&path, 512);
                axis_str_append(&path, dir.data);
                axis_str_path_join(&path, e.name);
                if (axis_fs_is_gguf(path.data) && axis_fs_file_size(path.data) > 0) {
                    memset(out, 0, sizeof(*out));
                    snprintf(out->id, sizeof(out->id), "imported-%s", e.name);
                    snprintf(out->display_name, sizeof(out->display_name),
                             "Imported (%s)", e.name);
                    snprintf(out->file, sizeof(out->file), "%s", e.name);
                    snprintf(out->path, sizeof(out->path), "%s", path.data);
                    out->size = e.size > 0 ? e.size : (uint64_t)(axis_fs_file_size(path.data) > 0
                                                       ? axis_fs_file_size(path.data) : 0);
                    out->ctx_len = 2048;
                    found = 1;
                    axis_str_free(&path);
                    break;
                }
                axis_str_free(&path);
            }
            axis_dir_close(d);
        }
    }
    axis_str_free(&dir);
    return found;
}

int axis_models_scan(const axis_manifest *m, const char *models_dir,
                     axis_installed_model *out, char *err, size_t errsz)
{
    if (!m || !models_dir || !out) return -1;

    for (int i = 0; i < m->count; i++) {
        const axis_manifest_entry *e = &m->entries[i];
        axis_str path;
        axis_str_init_cap(&path, 512);
        axis_str_append(&path, models_dir);
        axis_str_path_join(&path, e->id);
        axis_str_path_join(&path, e->file);

        int64_t sz = axis_fs_file_size(path.data);
        if (sz > 0) {
            memset(out, 0, sizeof(*out));
            snprintf(out->id, sizeof(out->id), "%s", e->id);
            snprintf(out->display_name, sizeof(out->display_name), "%s", e->display_name);
            snprintf(out->file, sizeof(out->file), "%s", e->file);
            snprintf(out->path, sizeof(out->path), "%s", path.data);
            out->size = (uint64_t)sz;
            snprintf(out->sha256, sizeof(out->sha256), "%s", e->sha256);
            out->ctx_len = e->context_length;
            axis_str_free(&path);
            return 1;
        }
        axis_str_free(&path);
    }

    if (scan_imported(models_dir, out)) return 1;

    if (err && errsz) snprintf(err, errsz, "No installed model");
    return 0;
}

/* ------------------------------------------------------------ installing -- */

typedef struct axis_install_ud {
    axis_models_progress cb;
    void *ud;
    uint64_t total_hint;
} axis_install_ud;

static int dl_progress(void *ud, uint64_t received, uint64_t total)
{
    axis_install_ud *ctx = (axis_install_ud *)ud;
    if (ctx->cb) ctx->cb(ctx->ud, AXIS_MOP_DOWNLOAD, received, total);
    return 0; /* cancellation handled via app-level flags + engine stop */
}

int axis_models_install(const axis_manifest_entry *e, const char *models_dir,
                        axis_models_progress cb, void *ud,
                        axis_installed_model *out, char *err, size_t errsz)
{
    if (!e || !models_dir || !out) return -1;

    axis_str entry_dir, part, final;
    axis_str_init_cap(&entry_dir, 512);
    axis_str_init_cap(&part, 512);
    axis_str_init_cap(&final, 512);

    axis_str_append(&entry_dir, models_dir);
    axis_str_path_join(&entry_dir, e->id);
    axis_str_append(&part, entry_dir.data);
    axis_str_path_join(&part, e->file);
    axis_str_append(&part, AXIS_PART_SUFFIX);
    axis_str_append(&final, entry_dir.data);
    axis_str_path_join(&final, e->file);

    int rc = -1;
    do {
        if (axis_fs_mkdir_p(entry_dir.data) != 0) {
            snprintf(err, errsz, "Cannot create model directory");
            break;
        }

        if (cb) cb(ud, AXIS_MOP_DOWNLOAD, 0, e->size_bytes);
        axis_install_ud dlud = { cb, ud, e->size_bytes };
        char dl_err[192];
        if (axis_download(e->url, part.data, dl_progress, &dlud, dl_err, sizeof(dl_err)) != 0) {
            snprintf(err, errsz, "Download failed: %s", dl_err);
            break;
        }

        int64_t got = axis_fs_file_size(part.data);
        if (got <= 0) {
            snprintf(err, errsz, "Downloaded model file is empty");
            break;
        }

        /* verify */
        if (cb) cb(ud, AXIS_MOP_VERIFY, 0, 0);
        char actual[65];
        if (axis_sha256_hex_file(part.data, actual) != 0) {
            snprintf(err, errsz, "Cannot hash downloaded file");
            break;
        }
        if (axis_manifest_sha_known(e->sha256) &&
            strcmp(actual, e->sha256) != 0) {
            snprintf(err, errsz, "SHA-256 mismatch — download corrupted");
            break;
        }

        /* install (atomic-ish: replace) */
        if (cb) cb(ud, AXIS_MOP_INSTALL, 0, 0);
        axis_fs_remove_file(final.data);
        if (axis_fs_copy(part.data, final.data, NULL, NULL) != 0) {
            snprintf(err, errsz, "Cannot move model into place");
            break;
        }
        axis_fs_remove_file(part.data);

        memset(out, 0, sizeof(*out));
        snprintf(out->id, sizeof(out->id), "%s", e->id);
        snprintf(out->display_name, sizeof(out->display_name), "%s", e->display_name);
        snprintf(out->file, sizeof(out->file), "%s", e->file);
        snprintf(out->path, sizeof(out->path), "%s", final.data);
        out->size = (uint64_t)got;
        snprintf(out->sha256, sizeof(out->sha256), "%s", actual);
        out->ctx_len = e->context_length;
        rc = 0;
    } while (0);

    if (rc != 0) axis_fs_remove_file(part.data);
    axis_str_free(&entry_dir);
    axis_str_free(&part);
    axis_str_free(&final);
    return rc;
}

/* --------------------------------------------------------------- import --- */

int axis_models_import(const char *src_path, const char *models_dir,
                       axis_models_progress cb, void *ud,
                       axis_installed_model *out, char *err, size_t errsz)
{
    if (!src_path || !models_dir || !out) return -1;

    if (!axis_fs_is_file(src_path)) {
        snprintf(err, errsz, "File not found: %s", src_path);
        return -1;
    }
    if (!axis_fs_is_gguf(src_path)) {
        snprintf(err, errsz, "Not a valid GGUF model file");
        return -1;
    }

    axis_str dest;
    axis_str_init_cap(&dest, 512);
    axis_str_append(&dest, models_dir);
    axis_str_path_join(&dest, AXIS_IMPORT_DIR);

    int rc = -1;
    do {
        if (axis_fs_mkdir_p(dest.data) != 0) {
            snprintf(err, errsz, "Cannot create import directory");
            break;
        }
        axis_str_path_join(&dest, axis_fs_base_name(src_path));

        if (cb) cb(ud, AXIS_MOP_IMPORT, 0, (uint64_t)axis_fs_file_size(src_path));
        if (axis_fs_copy(src_path, dest.data, NULL, NULL) != 0) {
            snprintf(err, errsz, "Import copy failed");
            break;
        }
        if (!axis_fs_is_gguf(dest.data)) {
            snprintf(err, errsz, "Imported file failed validation");
            break;
        }

        if (cb) cb(ud, AXIS_MOP_VERIFY, 0, 0);
        char actual[65];
        if (axis_sha256_hex_file(dest.data, actual) != 0) {
            snprintf(err, errsz, "Cannot hash imported file");
            break;
        }

        memset(out, 0, sizeof(*out));
        int64_t sz = axis_fs_file_size(dest.data);
        snprintf(out->id, sizeof(out->id), "imported-%s", axis_fs_base_name(dest.data));
        char size_txt[32];
        axis_models_format_bytes(sz > 0 ? (uint64_t)sz : 0, size_txt, sizeof(size_txt));
        snprintf(out->display_name, sizeof(out->display_name), "Imported model (%s)", size_txt);
        snprintf(out->file, sizeof(out->file), "%s", axis_fs_base_name(dest.data));
        snprintf(out->path, sizeof(out->path), "%s", dest.data);
        out->size = (uint64_t)(sz > 0 ? sz : 0);
        snprintf(out->sha256, sizeof(out->sha256), "%s", actual);
        out->ctx_len = 2048;
        rc = 0;
    } while (0);

    if (rc != 0 && dest.data) axis_fs_remove_file(dest.data);
    axis_str_free(&dest);
    return rc;
}

/* --------------------------------------------------------------- export --- */

typedef struct axis_export_ud {
    axis_models_progress cb;
    void *ud;
} axis_export_ud;

static int export_progress(void *ud, uint64_t copied, uint64_t total)
{
    axis_export_ud *ctx = (axis_export_ud *)ud;
    if (ctx->cb) ctx->cb(ctx->ud, AXIS_MOP_EXPORT, copied, total);
    return 0;
}

int axis_models_export(const axis_installed_model *inst, const char *dest_dir,
                       const char *filename_override,
                       axis_models_progress cb, void *ud,
                       char *err, size_t errsz)
{
    if (!inst || !dest_dir) return -1;
    if (!axis_fs_is_file(inst->path)) {
        snprintf(err, errsz, "Installed model file is missing");
        return -1;
    }
    if (!axis_fs_is_dir(dest_dir)) {
        snprintf(err, errsz, "Destination folder does not exist");
        return -1;
    }

    axis_str dest;
    axis_str_init_cap(&dest, 512);
    axis_str_append(&dest, dest_dir);
    axis_str_path_join(&dest, (filename_override && filename_override[0])
                                    ? filename_override
                                    : (inst->file[0] ? inst->file : "model.gguf"));

    axis_export_ud eud = { cb, ud };
    int rc = axis_fs_copy(inst->path, dest.data, export_progress, &eud);
    if (rc != 0) {
        snprintf(err, errsz, "Export copy failed");
    } else {
        /* sidecar metadata file for easier re-import / identification */
        axis_str meta;
        axis_str_init_cap(&meta, 512);
        axis_str_append(&meta, dest_dir);
        axis_str_path_join(&meta, "axis-model.txt");
        FILE *f = fopen(meta.data, "wb");
        if (f) {
            fprintf(f, "id=%s\nname=%s\nsha256=%s\nsize=%llu\n",
                    inst->id, inst->display_name, inst->sha256,
                    (unsigned long long)inst->size);
            fclose(f);
        }
        axis_str_free(&meta);
    }
    axis_str_free(&dest);
    return rc;
}

/* --------------------------------------------------------------- verify --- */

int axis_models_verify(const axis_installed_model *inst, const axis_manifest *m,
                       char *computed_hex, char *err, size_t errsz)
{
    if (!inst) return 0;
    if (!axis_fs_is_file(inst->path)) {
        snprintf(err, errsz, "Model file missing");
        return 0;
    }
    char actual[65];
    if (axis_sha256_hex_file(inst->path, actual) != 0) {
        snprintf(err, errsz, "Cannot hash model file");
        return 0;
    }
    if (computed_hex) snprintf(computed_hex, 65, "%s", actual);

    const axis_manifest_entry *e = m ? axis_manifest_by_id(m, inst->id) : NULL;
    if (!e || !axis_manifest_sha_known(e->sha256)) return 1; /* nothing to compare */
    return strcmp(actual, e->sha256) == 0;
}

/* --------------------------------------------------------------- remove --- */

int axis_models_remove(const axis_installed_model *inst, char *err, size_t errsz)
{
    if (!inst) return 0;
    if (!inst->path[0]) return 0;

    /* Manifest-installed models live in their own directory; imported ones
     * are single files inside imported/. */
    if (strstr(inst->path, AXIS_PART_SUFFIX)) return -1;

    axis_str parent;
    axis_str_init_cap(&parent, 512);
    axis_str_append(&parent, inst->path);
    /* find last separator */
    char *sep = NULL;
    for (char *p = parent.data; *p; p++) {
        if (*p == '/' || *p == '\\') sep = p;
    }
    if (sep) {
        int is_import = strstr(parent.data, AXIS_IMPORT_DIR) != NULL;
        if (is_import) {
            /* imported models are single files — remove only this file */
            axis_fs_remove_file(inst->path);
        } else {
            *sep = '\0'; /* parent = .../<entryId> */
            if (axis_fs_remove_tree(parent.data) != 0) {
                axis_fs_remove_file(inst->path);
            }
        }
    } else {
        axis_fs_remove_file(inst->path);
    }
    axis_str_free(&parent);
    (void)err;
    (void)errsz;
    return 0;
}
