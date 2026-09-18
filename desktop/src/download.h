/* download.h — HTTP(S) file download with progress + cancellation.
 *
 * Two implementations of the same tiny interface:
 *   download_winhttp.c — WinHTTP + Schannel (zero extra deps on Windows)
 *   download_curl.c    — libcurl (system package on Linux) */
#ifndef AXIS_DOWNLOAD_H
#define AXIS_DOWNLOAD_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Progress callback: (ud, received, total) -> 1 to cancel, 0 to continue.
 * total may be 0 when the server does not send Content-Length. */
typedef int (*axis_dl_progress)(void *ud, uint64_t received, uint64_t total);

/* Downloads url to dest_path (truncating). Returns 0 on success, -1 on
 * error (err gets a short message). Cancelling yields -1 with err
 * "cancelled". */
int axis_download(const char *url, const char *dest_path,
                  axis_dl_progress progress, void *ud,
                  char *err, size_t errsz);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_DOWNLOAD_H */
