/* platform_posix.c — Linux/POSIX implementation of the axis platform layer.
 *
 * Uses pthreads, $XDG_CONFIG_HOME (falling back to ~/.config), opendir/
 * readdir/stat, and clock_gettime for time. DPI scale is left at 1.0 —
 * XWayland/compositor scaling is handled by the OS for X11 windows. */
#ifndef _WIN32

#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include "platform.h"

/* ------------------------------------------------------------- threading */

struct axis_thread {
    pthread_t handle;
    int started;
};

struct axis_mutex {
    pthread_mutex_t mtx;
};

struct axis_thread_arg {
    axis_thread_fn fn;
    void *arg;
};

static void *axis_thread_trampoline(void *p)
{
    struct axis_thread_arg *a = (struct axis_thread_arg *)p;
    axis_thread_fn fn = a->fn;
    void *arg = a->arg;
    free(a);
    fn(arg);
    return NULL;
}

axis_thread *axis_thread_start(axis_thread_fn fn, void *arg)
{
    struct axis_thread_arg *a = (struct axis_thread_arg *)malloc(sizeof(*a));
    if (!a) return NULL;
    a->fn = fn;
    a->arg = arg;

    axis_thread *t = (axis_thread *)calloc(1, sizeof(*t));
    if (!t) { free(a); return NULL; }
    if (pthread_create(&t->handle, NULL, axis_thread_trampoline, a) != 0) {
        free(a);
        free(t);
        return NULL;
    }
    t->started = 1;
    return t;
}

void axis_thread_join(axis_thread *t)
{
    if (!t) return;
    if (t->started) pthread_join(t->handle, NULL);
    free(t);
}

axis_mutex *axis_mutex_create(void)
{
    axis_mutex *m = (axis_mutex *)malloc(sizeof(*m));
    if (!m) return NULL;
    if (pthread_mutex_init(&m->mtx, NULL) != 0) { free(m); return NULL; }
    return m;
}

void axis_mutex_free(axis_mutex *m)
{
    if (!m) return;
    pthread_mutex_destroy(&m->mtx);
    free(m);
}

void axis_mutex_lock(axis_mutex *m)
{
    if (m) pthread_mutex_lock(&m->mtx);
}

void axis_mutex_unlock(axis_mutex *m)
{
    if (m) pthread_mutex_unlock(&m->mtx);
}

/* ------------------------------------------------------------ time / dpi */

uint64_t axis_time_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

void axis_enable_dpi_awareness(void)
{
    /* X11 windows are scaled by the compositor; nothing to do here. */
}

float axis_get_dpi_scale(void)
{
    return 1.0f;
}

/* ---------------------------------------------------------------- paths */

static int axis_mkdir_p(const char *path)
{
    char buf[AXIS_PATH_MAX];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(buf)) return -1;
    memcpy(buf, path, len + 1);
    while (len > 1 && buf[len - 1] == '/') buf[--len] = '\0';

    for (size_t i = 1; buf[i]; i++) {
        if (buf[i] == '/') {
            buf[i] = '\0';
            if (mkdir(buf, 0755) != 0 && errno != EEXIST) return -1;
            buf[i] = '/';
        }
    }
    if (mkdir(buf, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

int axis_paths_config_dir(char *out, size_t n)
{
    const char *xdg = getenv("XDG_CONFIG_HOME");
    if (xdg && xdg[0]) {
        if ((size_t)snprintf(out, n, "%s/axis-translate", xdg) >= n) return -1;
    } else {
        const char *home = getenv("HOME");
        if (!home || !home[0]) return -1;
        if ((size_t)snprintf(out, n, "%s/.config/axis-translate", home) >= n) return -1;
    }
    return axis_mkdir_p(out);
}

int axis_paths_models_dir(char *out, size_t n)
{
    if (axis_paths_config_dir(out, n) != 0) return -1;
    size_t len = strlen(out);
    if ((size_t)snprintf(out + len, n - len, "/models") >= (size_t)(n - len)) return -1;
    return axis_mkdir_p(out);
}

/* ----------------------------------------------------- directory listing */

struct axis_dir {
    DIR *dir;
    char base[AXIS_PATH_MAX];
};

axis_dir *axis_dir_open(const char *path)
{
    if (!path || !path[0]) return NULL;
    DIR *d = opendir(path);
    if (!d) return NULL;
    axis_dir *ad = (axis_dir *)calloc(1, sizeof(*ad));
    if (!ad) { closedir(d); return NULL; }
    ad->dir = d;
    snprintf(ad->base, sizeof(ad->base), "%s", path);
    /* strip a trailing slash (but keep "/") */
    size_t len = strlen(ad->base);
    while (len > 1 && ad->base[len - 1] == '/') ad->base[--len] = '\0';
    return ad;
}

int axis_dir_next(axis_dir *d, axis_dirent *out)
{
    if (!d || !out) return -1;
    struct dirent *e;
    for (;;) {
        errno = 0;
        e = readdir(d->dir);
        if (!e) return (errno != 0) ? -1 : 1;
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        if (strlen(e->d_name) >= sizeof(out->name)) continue;

        snprintf(out->name, sizeof(out->name), "%s", e->d_name);
        out->is_dir = 0;
        out->size = 0;
#ifdef DT_DIR
        if (e->d_type == DT_DIR) {
            out->is_dir = 1;
        } else if (e->d_type == DT_REG) {
            out->is_dir = 0;
        } else
#endif
        {
            /* d_type unavailable (or unknown): fall back to stat */
            char full[AXIS_PATH_MAX];
            struct stat st;
            if ((size_t)snprintf(full, sizeof(full), "%s/%s", d->base, e->d_name) < sizeof(full) &&
                stat(full, &st) == 0) {
                out->is_dir = S_ISDIR(st.st_mode) ? 1 : 0;
                out->size = (uint64_t)st.st_size;
            }
        }
        return 0;
    }
}

void axis_dir_close(axis_dir *d)
{
    if (!d) return;
    closedir(d->dir);
    free(d);
}

#endif /* !_WIN32 */
