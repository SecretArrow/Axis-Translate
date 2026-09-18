/* fs.h — file utilities: existence, size, mkdir -p, copy with progress,
 * remove file / recursive remove, GGUF magic sniff. */
#ifndef AXIS_FS_H
#define AXIS_FS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int axis_fs_exists(const char *path);
int axis_fs_is_file(const char *path);
int axis_fs_is_dir(const char *path);
/* 0 = missing, else size in bytes (-1 on error) */
int64_t axis_fs_file_size(const char *path);

/* mkdir -p for full path (handles parents; Windows/POSIX separators). */
int axis_fs_mkdir_p(const char *path);

/* Reads a whole file into a heap buffer (NUL-terminated). Returns NULL on
 * failure. Caller frees. *out_len may be NULL. */
char *axis_fs_read_all(const char *path, size_t *out_len);

/* Copy progress callback: (ud, copied, total) -> 1 to cancel, 0 to continue. */
typedef int (*axis_copy_cb)(void *ud, uint64_t copied, uint64_t total);

int axis_fs_copy(const char *src, const char *dst, axis_copy_cb cb, void *ud);

int axis_fs_remove_file(const char *path);
/* Removes a directory and everything below it. */
int axis_fs_remove_tree(const char *path);

/* 1 when the file starts with the GGUF magic ("GGUF"). */
int axis_fs_is_gguf(const char *path);

/* Extracts the base name (after the last separator). */
const char *axis_fs_base_name(const char *path);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_FS_H */
