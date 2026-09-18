/* main.c — entry point.
 *
 * Flags:
 *   --help     usage
 *   --version  app + llama.cpp version
 *   --smoke    run 5 frames and exit 0 (CI smoke test; same as AXIS_SMOKE=1)
 */
#include "app.h"
#include "engine.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void print_usage(void)
{
    printf("Axis Translate Desktop — offline AI translation (llama.cpp, C edition)\n"
           "\n"
           "Usage: axis-translate [options]\n"
           "\n"
           "Options:\n"
           "  --help     Show this help and exit\n"
           "  --version  Print the version and exit\n"
           "  --smoke    Render a few frames and exit (headless CI check)\n"
           "\n"
           "All models and settings live in the user config directory; the\n"
           "first model is downloaded on demand from the built-in manifest.\n");
}

static void print_version(void)
{
    printf("Axis Translate Desktop 1.0\n");
    printf("llama.cpp %s\n", axis_engine_llama_version());
}

int main(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            print_usage();
            return 0;
        }
        if (strcmp(argv[i], "--version") == 0) {
            print_version();
            return 0;
        }
        if (strcmp(argv[i], "--smoke") == 0) {
#ifdef _WIN32
            _putenv_s("AXIS_SMOKE", "1");
#else
            setenv("AXIS_SMOKE", "1", 1);
#endif
            continue;
        }
    }

    axis_enable_dpi_awareness();

    axis_app app;
    char err[256];
    if (axis_app_init(&app, err, sizeof(err)) != 0) {
        fprintf(stderr, "axis-translate: %s\n", err);
        return 1;
    }

    axis_app_run(&app);
    axis_app_shutdown(&app);
    return 0;
}
