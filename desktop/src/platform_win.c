/* platform_win.c — Windows implementation of the axis platform layer.
 *
 * Uses Win32 APIs only (no extra dependencies): _beginthreadex for threads,
 * CRITICAL_SECTION for the mutex, SHGetFolderPathA for %LOCALAPPDATA%,
 * FindFirstFileEx for directory iteration, and GDI for DPI. */
#ifdef _WIN32

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <shlobj.h>
#include <process.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "platform.h"

/* ------------------------------------------------------------- threading */

struct axis_thread {
    HANDLE handle;
};

struct axis_mutex {
    CRITICAL_SECTION cs;
};

static unsigned __stdcall axis_thread_trampoline(void *arg_)
{
    struct axis_thread_arg {
        axis_thread_fn fn;
        void *arg;
    } *a = (struct axis_thread_arg *)arg_;
    struct axis_thread_arg local = *a;
    free(a);
    local.fn(local.arg);
    return 0;
}

axis_thread *axis_thread_start(axis_thread_fn fn, void *arg)
{
    struct axis_thread_arg {
        axis_thread_fn fn;
        void *arg;
    } *a = (struct axis_thread_arg *)malloc(sizeof(*a));
    if (!a) return NULL;
    a->fn = fn;
    a->arg = arg;

    axis_thread *t = (axis_thread *)malloc(sizeof(*t));
    if (!t) { free(a); return NULL; }

    uintptr_t h = _beginthreadex(NULL, 0, axis_thread_trampoline, a, 0, NULL);
    if (h == 0) { free(a); free(t); return NULL; }
    t->handle = (HANDLE)h;
    return t;
}

void axis_thread_join(axis_thread *t)
{
    if (!t) return;
    WaitForSingleObject(t->handle, INFINITE);
    CloseHandle(t->handle);
    free(t);
}

axis_mutex *axis_mutex_create(void)
{
    axis_mutex *m = (axis_mutex *)malloc(sizeof(*m));
    if (!m) return NULL;
    InitializeCriticalSection(&m->cs);
    return m;
}

void axis_mutex_free(axis_mutex *m)
{
    if (!m) return;
    DeleteCriticalSection(&m->cs);
    free(m);
}

void axis_mutex_lock(axis_mutex *m)
{
    if (m) EnterCriticalSection(&m->cs);
}

void axis_mutex_unlock(axis_mutex *m)
{
    if (m) LeaveCriticalSection(&m->cs);
}

/* ------------------------------------------------------------ time / dpi */

uint64_t axis_time_ms(void)
{
    return (uint64_t)GetTickCount64();
}

void axis_enable_dpi_awareness(void)
{
    SetProcessDPIAware();
}

float axis_get_dpi_scale(void)
{
    HDC dc = GetDC(NULL);
    if (!dc) return 1.0f;
    int dpi = GetDeviceCaps(dc, LOGPIXELSX);
    ReleaseDC(NULL, dc);
    if (dpi <= 0) return 1.0f;
    float scale = (float)dpi / 96.0f;
    if (scale < 1.0f) scale = 1.0f;
    return scale;
}

/* ---------------------------------------------------------------- paths */

static int axis_mkdir_p(const char *path)
{
    /* CreateDirectory does not create parents, so walk the components. */
    char buf[AXIS_PATH_MAX];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(buf)) return -1;
    memcpy(buf, path, len + 1);

    /* strip trailing separators (keep root like "C:\") */
    while (len > 3 && (buf[len - 1] == '\\' || buf[len - 1] == '/')) buf[--len] = '\0';

    for (size_t i = 0; buf[i]; i++) {
        if (buf[i] == '\\' || buf[i] == '/') {
            if (i == 0) continue;
            char c = buf[i];
            buf[i] = '\0';
            if (GetFileAttributesA(buf) == INVALID_FILE_ATTRIBUTES) {
                if (!CreateDirectoryA(buf, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
                    return -1;
            }
            buf[i] = c;
        }
    }
    if (GetFileAttributesA(buf) == INVALID_FILE_ATTRIBUTES) {
        if (!CreateDirectoryA(buf, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
            return -1;
    }
    return 0;
}

int axis_paths_config_dir(char *out, size_t n)
{
    char appdata[AXIS_PATH_MAX];
    if (SUCCEEDED(SHGetFolderPathA(NULL, CSIDL_LOCAL_APPDATA | CSIDL_FLAG_CREATE,
                                   NULL, 0, appdata))) {
        _snprintf_s(out, n, _TRUNCATE, "%s\\AxisTranslate", appdata);
    } else {
        _snprintf_s(out, n, _TRUNCATE, "AxisTranslate");
    }
    if (axis_mkdir_p(out) != 0) return -1;
    return 0;
}

int axis_paths_models_dir(char *out, size_t n)
{
    if (axis_paths_config_dir(out, n) != 0) return -1;
    size_t len = strlen(out);
    int written = _snprintf_s(out + len, n - len, _TRUNCATE, "\\models");
    if (written <= 0) return -1;
    if (axis_mkdir_p(out) != 0) return -1;
    return 0;
}

/* ----------------------------------------------------- directory listing */

struct axis_dir {
    HANDLE handle;
    int done;
    WIN32_FIND_DATAA data;
    int has_pending;
};

axis_dir *axis_dir_open(const char *path)
{
    if (!path || !path[0]) return NULL;
    char pattern[AXIS_PATH_MAX];
    size_t len = strlen(path);
    /* strip trailing separators except drive roots like "C:\" */
    while (len > 3 && (path[len - 1] == '\\' || path[len - 1] == '/')) len--;
    if (len + 3 >= sizeof(pattern)) return NULL;
    memcpy(pattern, path, len);
    pattern[len] = '\0';
    strcat(pattern, "\\*");

    axis_dir *d = (axis_dir *)calloc(1, sizeof(*d));
    if (!d) return NULL;
    d->handle = FindFirstFileExA(pattern, FindExInfoBasic, &d->data,
                                 FindExSearchNameMatch, NULL, 0);
    if (d->handle == INVALID_HANDLE_VALUE) {
        free(d);
        return NULL;
    }
    d->has_pending = 1;
    return d;
}

int axis_dir_next(axis_dir *d, axis_dirent *out)
{
    if (!d || !out) return -1;
    for (;;) {
        if (!d->has_pending) {
            if (d->done) return 1;
            if (!FindNextFileA(d->handle, &d->data)) {
                d->done = 1;
                return 1;
            }
        }
        d->has_pending = 0;

        const char *name = d->data.cFileName;
        if (strcmp(name, ".") == 0 || strcmp(name, "..") == 0) continue;

        if (strlen(name) >= sizeof(out->name)) continue;
        strcpy(out->name, name);
        out->is_dir = (d->data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) ? 1 : 0;
        out->size = ((uint64_t)d->data.nFileSizeHigh << 32) | (uint64_t)d->data.nFileSizeLow;
        return 0;
    }
}

void axis_dir_close(axis_dir *d)
{
    if (!d) return;
    if (d->handle != INVALID_HANDLE_VALUE) FindClose(d->handle);
    free(d);
}

#endif /* _WIN32 */
