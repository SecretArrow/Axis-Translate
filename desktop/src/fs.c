/* fs.c — file utilities shared by both platforms. */
#include "fs.h"
#include "platform.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <direct.h>
#else
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#endif

int axis_fs_exists(const char *path)
{
#ifdef _WIN32
    return GetFileAttributesA(path) != INVALID_FILE_ATTRIBUTES;
#else
    struct stat st;
    return stat(path, &st) == 0;
#endif
}

int axis_fs_is_file(const char *path)
{
#ifdef _WIN32
    DWORD a = GetFileAttributesA(path);
    return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
#else
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
#endif
}

int axis_fs_is_dir(const char *path)
{
#ifdef _WIN32
    DWORD a = GetFileAttributesA(path);
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
#else
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
#endif
}

int64_t axis_fs_file_size(const char *path)
{
#ifdef _WIN32
    WIN32_FILE_ATTRIBUTE_DATA fad;
    if (!GetFileAttributesExA(path, GetFileExInfoStandard, &fad)) return -1;
    if (fad.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) return -1;
    return (int64_t)(((uint64_t)fad.nFileSizeHigh << 32) | (uint64_t)fad.nFileSizeLow);
#else
    struct stat st;
    if (stat(path, &st) != 0) return -1;
    if (!S_ISREG(st.st_mode)) return -1;
    return (int64_t)st.st_size;
#endif
}

int axis_fs_mkdir_p(const char *path)
{
#ifdef _WIN32
    char buf[1024];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(buf)) return -1;
    memcpy(buf, path, len + 1);
    while (len > 3 && (buf[len - 1] == '\\' || buf[len - 1] == '/')) buf[--len] = '\0';
    for (size_t i = 0; buf[i]; i++) {
        if ((buf[i] == '\\' || buf[i] == '/') && i > 0) {
            char c = buf[i];
            buf[i] = '\0';
            if (GetFileAttributesA(buf) == INVALID_FILE_ATTRIBUTES) {
                if (!CreateDirectoryA(buf, NULL)) return -1;
            }
            buf[i] = c;
        }
    }
    if (GetFileAttributesA(buf) == INVALID_FILE_ATTRIBUTES) {
        if (!CreateDirectoryA(buf, NULL)) return -1;
    }
    return 0;
#else
    char buf[1024];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(buf)) return -1;
    memcpy(buf, path, len + 1);
    while (len > 1 && buf[len - 1] == '/') buf[--len] = '\0';
    for (size_t i = 1; buf[i]; i++) {
        if (buf[i] == '/') {
            buf[i] = '\0';
            if (mkdir(buf, 0755) != 0) return -1;
            buf[i] = '/';
        }
    }
    if (mkdir(buf, 0755) != 0) return -1;
    return 0;
#endif
}

char *axis_fs_read_all(const char *path, size_t *out_len)
{
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long sz = ftell(f);
    if (sz < 0) { fclose(f); return NULL; }
    if (fseek(f, 0, SEEK_SET) != 0) { fclose(f); return NULL; }

    char *buf = (char *)malloc((size_t)sz + 1);
    if (!buf) { fclose(f); return NULL; }
    if (sz > 0 && fread(buf, (size_t)sz, 1, f) != 1) {
        free(buf);
        fclose(f);
        return NULL;
    }
    fclose(f);
    buf[sz] = '\0';
    if (out_len) *out_len = (size_t)sz;
    return buf;
}

int axis_fs_copy(const char *src, const char *dst, axis_copy_cb cb, void *ud)
{
    int64_t src_size = axis_fs_file_size(src);
    if (src_size < 0) return -1;

    FILE *in = fopen(src, "rb");
    if (!in) return -1;
    FILE *out = fopen(dst, "wb");
    if (!out) { fclose(in); return -1; }

    char buf[64 * 1024];
    uint64_t copied = 0;
    int rc = 0;
    for (;;) {
        size_t n = fread(buf, 1, sizeof(buf), in);
        if (n == 0) {
            if (ferror(in)) rc = -1;
            break;
        }
        if (fwrite(buf, 1, n, out) != n) { rc = -1; break; }
        copied += (uint64_t)n;
        if (cb && cb(ud, copied, (uint64_t)src_size)) { rc = -2; break; } /* cancelled */
    }

    if (fflush(out) != 0) rc = -1;
    fclose(in);
    if (fclose(out) != 0) rc = -1;
    if (rc != 0) remove(dst);
    return rc;
}

int axis_fs_remove_file(const char *path)
{
    return remove(path);
}

int axis_fs_remove_tree(const char *path)
{
    if (!axis_fs_exists(path)) return 0;
    if (axis_fs_is_file(path)) return remove(path);

    /* directory: iterate + recurse via platform_dir */
    axis_dir *d = axis_dir_open(path);
    if (!d) return -1;

    char child[1024];
    axis_dirent e;
    int rc = 0;
    while (axis_dir_next(d, &e) == 0) {
#ifdef _WIN32
        if ((size_t)snprintf(child, sizeof(child), "%s\\%s", path, e.name) >= sizeof(child)) { rc = -1; break; }
#else
        if ((size_t)snprintf(child, sizeof(child), "%s/%s", path, e.name) >= sizeof(child)) { rc = -1; break; }
#endif
        if (axis_fs_remove_tree(child) != 0) { rc = -1; break; }
    }
    axis_dir_close(d);
    if (rc != 0) return rc;

#ifdef _WIN32
    if (!RemoveDirectoryA(path)) return -1;
#else
    if (rmdir(path) != 0) return -1;
#endif
    return 0;
}

int axis_fs_is_gguf(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    char magic[4];
    size_t n = fread(magic, 1, 4, f);
    fclose(f);
    if (n != 4) return 0;
    return memcmp(magic, "GGUF", 4) == 0;
}

const char *axis_fs_base_name(const char *path)
{
    if (!path) return "";
    const char *slash = strrchr(path, '/');
    const char *bslash = strrchr(path, '\\');
    const char *cut = (slash && bslash) ? (slash > bslash ? slash : bslash)
                                        : (slash ? slash : bslash);
    return cut ? cut + 1 : path;
}
