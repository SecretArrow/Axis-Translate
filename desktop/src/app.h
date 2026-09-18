/* app.h — application state, worker-thread orchestration and job queue.
 *
 * One persistent worker thread owns the llama.cpp engine and executes every
 * long-running operation (translation, model install/import/export/remove,
 * verify, engine reload). The UI thread never blocks: it reads the shared
 * state under a mutex at frame rate and renders progress. */
#ifndef AXIS_APP_H
#define AXIS_APP_H

#include "backend.h"
#include "manifest.h"
#include "models.h"
#include "store.h"

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AXIS_INPUT_MAX (64 * 1024)
#define AXIS_OUTPUT_MAX (64 * 1024)

typedef enum {
    AXIS_SCREEN_TRANSLATE = 0,
    AXIS_SCREEN_MODELS = 1,
    AXIS_SCREEN_HISTORY = 2,
    AXIS_SCREEN_SETTINGS = 3,
} axis_screen;

typedef enum {
    AXIS_JOB_NONE = 0,
    AXIS_JOB_TRANSLATE,
    AXIS_JOB_INSTALL,
    AXIS_JOB_IMPORT,
    AXIS_JOB_EXPORT,
    AXIS_JOB_REMOVE,
    AXIS_JOB_VERIFY,
    AXIS_JOB_RELOAD,
} axis_job_type;

#define AXIS_BROWSER_MAX_ROWS 512

typedef struct axis_browser {
    int active;                 /* modal visible */
    int mode_open;              /* 1 = pick .gguf (import), 0 = pick folder (export) */
    char dir[AXIS_PATH_MAX];    /* current directory */
    char filename[256];         /* export filename edit buffer */
    int filename_len;
    int selected;               /* selected row index, -1 none */
    /* listing snapshot (rebuilt on directory change) */
    int count;
    axis_dirent rows[AXIS_BROWSER_MAX_ROWS];
    char err[128];
} axis_browser;

typedef struct axis_app {
    /* backend */
    axis_backend *be;
    int quit;

    /* config + data */
    char config_dir[AXIS_PATH_MAX];
    char models_dir[AXIS_PATH_MAX];
    axis_config cfg;
    axis_manifest manifest;
    axis_history history;

    /* shared state (guarded by mu) */
    axis_mutex *mu;
    axis_installed_model installed;      /* valid when installed.id[0] != 0 */
    int op_busy;
    axis_model_op op;
    char op_entry_id[64];
    uint64_t op_done, op_total;
    char op_error[256];
    char op_message[256];
    int verify_result;                   /* -1 unknown, 0 fail, 1 ok */

    int translating;
    int stop_requested;
    char trans_output[AXIS_OUTPUT_MAX];
    size_t trans_output_len;
    char trans_status[256];
    char trans_error[256];
    double last_tokens_per_sec;

    /* UI-only state (not mutex-guarded; touched by UI thread;
     * app->input is also read by the worker under mu while translating,
     * and the UI disables editing while busy) */
    int screen;
    char input[AXIS_INPUT_MAX];
    int input_len;
    int input_active;
    int src_lang, tgt_lang;              /* langs indexes (src may be AUTO) */
    axis_browser browser;
    char toast[256];                     /* transient status message */
    uint64_t toast_until_ms;

    /* job mailbox (set by UI, consumed by worker) */
    volatile int job_pending;
    axis_job_type job_type;
    int job_entry_index;
    char job_path[AXIS_PATH_MAX];
    char job_path2[256];   /* secondary path (e.g. export filename) */

    /* worker */
    axis_thread *worker;
    volatile int worker_running;
} axis_app;

/* Lifecycle -------------------------------------------------------------- */

int axis_app_init(axis_app *app, char *err, size_t errsz);
void axis_app_run(axis_app *app);
void axis_app_shutdown(axis_app *app);

/* Jobs (called from the UI thread) --------------------------------------- */

void axis_app_submit(axis_app *app, axis_job_type type, int entry_index, const char *path);
int axis_app_busy(axis_app *app);
void axis_app_request_stop(axis_app *app);

/* Helpers for the UI ------------------------------------------------------ */

void axis_app_set_screen(axis_app *app, int screen);
void axis_app_toast(axis_app *app, const char *fmt, ...);
void axis_app_save_config_now(axis_app *app);

/* Screens (implemented in ui_*.c) */
void ui_translate_screen(axis_app *app);
void ui_models_screen(axis_app *app);
void ui_history_screen(axis_app *app);
void ui_settings_screen(axis_app *app);
void ui_browser_modal(axis_app *app);   /* draws when app->browser.active */

#ifdef __cplusplus
}
#endif

#endif /* AXIS_APP_H */
