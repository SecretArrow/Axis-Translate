/* download_curl.c — Linux download implementation via libcurl.
 *
 * Uses the system libcurl (Ubuntu: libcurl4-openssl-dev). TLS certificates
 * come from the system store; redirects are followed; HTTP errors fail. */
#ifndef _WIN32

#include <curl/curl.h>

#include <stdio.h>
#include <string.h>

#include "download.h"

typedef struct axis_dl_ctx {
    FILE *out;
    axis_dl_progress progress;
    void *ud;
    uint64_t received;
    uint64_t total;
} axis_dl_ctx;

static size_t on_write(char *ptr, size_t size, size_t nmemb, void *userdata)
{
    axis_dl_ctx *dctx = (axis_dl_ctx *)userdata;
    size_t n = size * nmemb;
    if (n > 0 && fwrite(ptr, 1, n, dctx->out) != n) return 0; /* signals error */
    dctx->received += (uint64_t)n;
    return n;
}

static int on_progress(void *userdata, curl_off_t dltotal, curl_off_t dlnow,
                       curl_off_t ultotal, curl_off_t ulnow)
{
    axis_dl_ctx *dctx = (axis_dl_ctx *)userdata;
    (void)ultotal;
    (void)ulnow;
    /* prefer our byte-accurate count (dlnow may lag behind written bytes) */
    if (dctx->progress) {
        return dctx->progress(dctx->ud, dctx->received, (uint64_t)dltotal > 0 ? (uint64_t)dltotal : dctx->total);
    }
    return 0;
}

int axis_download(const char *url, const char *dest_path,
                  axis_dl_progress progress, void *ud,
                  char *err, size_t errsz)
{
    if (!url || !url[0] || !dest_path) {
        if (err && errsz) snprintf(err, errsz, "Bad download arguments");
        return -1;
    }

    FILE *out = fopen(dest_path, "wb");
    if (!out) {
        if (err && errsz) snprintf(err, errsz, "Cannot open destination file");
        return -1;
    }

    axis_dl_ctx dctx = { out, progress, ud, 0, 0 };

    CURL *curl = curl_easy_init();
    if (!curl) {
        fclose(out);
        if (err && errsz) snprintf(err, errsz, "curl_easy_init failed");
        return -1;
    }

    int rc = -1;

    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "AxisTranslate/1.0");
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 30L);
    curl_easy_setopt(curl, CURLOPT_LOW_SPEED_LIMIT, 1024L);
    curl_easy_setopt(curl, CURLOPT_LOW_SPEED_TIME, 120L);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &dctx);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, on_write);
    curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
    curl_easy_setopt(curl, CURLOPT_XFERINFODATA, &dctx);
    curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, on_progress);

    CURLcode cres = curl_easy_perform(curl);
    if (cres == CURLE_OK) {
        if (dctx.received == 0) {
            if (err && errsz) snprintf(err, errsz, "Downloaded file is empty");
        } else if (fflush(out) != 0) {
            if (err && errsz) snprintf(err, errsz, "Flush failed");
        } else {
            rc = 0;
        }
    } else if (cres == CURLE_ABORTED_BY_CALLBACK) {
        if (err && errsz) snprintf(err, errsz, "cancelled");
    } else {
        if (err && errsz) snprintf(err, errsz, "%s", curl_easy_strerror(cres));
    }

    fclose(out);
    if (rc != 0) remove(dest_path);
    curl_easy_cleanup(curl);
    return rc;
}

#endif /* !_WIN32 */
