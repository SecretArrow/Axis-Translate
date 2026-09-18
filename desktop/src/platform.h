/* platform.h — thin cross-platform (Windows / POSIX) OS abstraction layer.
 *
 * Covers: worker thread, mutex, monotonic time, config/models directories,
 * directory iteration, and DPI scale. Implemented per-OS in
 * platform_win.c and platform_posix.c.
 *
 * Axis Translate desktop — pure C11 application layer. */
#ifndef AXIS_PLATFORM_H
#define AXIS_PLATFORM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AXIS_PATH_MAX 1024

/* ------------------------------------------------------------- threading */

typedef void (*axis_thread_fn)(void *arg);

typedef struct axis_thread axis_thread;

axis_thread *axis_thread_start(axis_thread_fn fn, void *arg);
void axis_thread_join(axis_thread *t);

typedef struct axis_mutex axis_mutex;

axis_mutex *axis_mutex_create(void);
void axis_mutex_free(axis_mutex *m);
void axis_mutex_lock(axis_mutex *m);
void axis_mutex_unlock(axis_mutex *m);

/* ------------------------------------------------------------ time / dpi */

/* Milliseconds on a monotonic-ish clock (wall clock is acceptable). */
uint64_t axis_time_ms(void);

/* UI scale factor (Windows: per primary monitor; elsewhere 1.0f). */
float axis_get_dpi_scale(void);
void axis_enable_dpi_awareness(void);

/* ---------------------------------------------------------------- paths */

/* App config dir (created on demand):  <config>/AxisTranslate
 *   Windows: %LOCALAPPDATA%\AxisTranslate
 *   Linux:   $XDG_CONFIG_HOME/AxisTranslate  or  ~/.config/AxisTranslate
 * Models live under <config>/AxisTranslate/models/<entryId>/<file>.
 * Returns 0 on success. */
int axis_paths_config_dir(char *out, size_t n);
int axis_paths_models_dir(char *out, size_t n);

/* ----------------------------------------------------- directory listing */

typedef struct axis_dirent {
    char name[256];
    int is_dir;
    uint64_t size;
} axis_dirent;

typedef struct axis_dir axis_dir;

axis_dir *axis_dir_open(const char *path);
/* 0 = entry filled, 1 = end of listing, -1 = error */
int axis_dir_next(axis_dir *d, axis_dirent *out);
void axis_dir_close(axis_dir *d);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_PLATFORM_H */
