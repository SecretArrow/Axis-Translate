/* download_winhttp.c — Windows download implementation via WinHTTP.
 *
 * WinHTTP uses Schannel for TLS, so no OpenSSL dependency is needed.
 * Redirects are followed automatically by the default flag set. */
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <winhttp.h>

#include <stdio.h>
#include <string.h>

#include "download.h"

typedef struct axis_dl_ctx {
    axis_dl_progress progress;
    void *ud;
    uint64_t received;
    uint64_t total;
    int cancelled;
} axis_dl_ctx;

static void set_err(char *err, size_t errsz, const char *msg)
{
    if (err && errsz) snprintf(err, errsz, "%s", msg);
}

int axis_download(const char *url, const char *dest_path,
                  axis_dl_progress progress, void *ud,
                  char *err, size_t errsz)
{
    axis_dl_ctx dctx = { progress, ud, 0, 0, 0 };

    /* ---- split the URL: scheme + host + path -------------------------- */
    /* Only https://... and http://... are supported. */
    int secure = 1;
    const char *p = url;
    if (strncmp(p, "https://", 8) == 0) {
        p += 8;
    } else if (strncmp(p, "http://", 7) == 0) {
        p += 7;
        secure = 0;
    } else {
        set_err(err, errsz, "Only http(s) URLs are supported");
        return -1;
    }

    char host[256];
    char path[1024];
    const char *slash = strchr(p, '/');
    if (!slash) {
        if (strlen(p) == 0 || strlen(p) >= sizeof(host)) { set_err(err, errsz, "Bad URL"); return -1; }
        strcpy(host, p);
        strcpy(path, "/");
    } else {
        size_t host_len = (size_t)(slash - p);
        if (host_len == 0 || host_len >= sizeof(host) || strlen(slash) >= sizeof(path)) {
            set_err(err, errsz, "Bad URL");
            return -1;
        }
        memcpy(host, p, host_len);
        host[host_len] = '\0';
        strcpy(path, slash);
    }

    /* strip userinfo if present */
    char *at = strchr(host, '@');
    if (at) memmove(host, at + 1, strlen(at + 1) + 1);

    /* default port (0 = scheme default) */
    INTERNET_PORT port = secure ? INTERNET_DEFAULT_HTTPS_PORT : INTERNET_DEFAULT_HTTP_PORT;

    /* ---- connect -------------------------------------------------------- */
    HINTERNET session = WinHttpOpen(L"AxisTranslate/" L"1.0",
                                    WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                    WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) { set_err(err, errsz, "WinHttpOpen failed"); return -1; }

    HINTERNET connect = NULL, request = NULL;
    FILE *out = NULL;
    int rc = -1;

    /* UTF-16 copies for the wide-char WinHTTP API */
    wchar_t whost[256];
    MultiByteToWideChar(CP_UTF8, 0, host, -1, whost, (int)(sizeof(whost) / sizeof(whost[0])));
    wchar_t wpath[1024];
    MultiByteToWideChar(CP_UTF8, 0, path, -1, wpath, (int)(sizeof(wpath) / sizeof(wpath[0])));

    connect = WinHttpConnect(session, whost, port, 0);
    if (!connect) { set_err(err, errsz, "WinHttpConnect failed"); goto done; }

    request = WinHttpOpenRequest(connect, L"GET", wpath, NULL, WINHTTP_NO_REFERER,
                                 WINHTTP_DEFAULT_ACCEPT_TYPES,
                                 secure ? WINHTTP_FLAG_SECURE : 0);
    if (!request) { set_err(err, errsz, "WinHttpOpenRequest failed"); goto done; }

    /* ~3 min total for headers: big model servers are sometimes slow */
    DWORD recv_timeout = 180000;
    WinHttpSetTimeouts(request, 30000, 30000, recv_timeout, 0);

    if (!WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
        !WinHttpReceiveResponse(request, NULL)) {
        set_err(err, errsz, "HTTP request failed");
        goto done;
    }

    /* ---- status --------------------------------------------------------- */
    DWORD status = 0, size = sizeof(status);
    if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                              WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX)) {
        set_err(err, errsz, "Failed to read HTTP status");
        goto done;
    }
    if (status < 200 || status >= 300) {
        if (err && errsz) snprintf(err, errsz, "HTTP %lu", (unsigned long)status);
        goto done;
    }

    /* ---- content length --------------------------------------------------- */
    DWORD cl = 0;
    size = sizeof(cl);
    if (WinHttpQueryHeaders(request, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &cl, &size, WINHTTP_NO_HEADER_INDEX)) {
        dctx.total = (uint64_t)cl;
    }

    /* ---- download loop ---------------------------------------------------- */
    out = fopen(dest_path, "wb");
    if (!out) { set_err(err, errsz, "Cannot open destination file"); goto done; }

    char buf[64 * 1024];
    DWORD fetched = 0;
    for (;;) {
        if (!WinHttpReadData(request, buf, sizeof(buf), &fetched)) {
            set_err(err, errsz, "Read failed");
            goto done;
        }
        if (fetched == 0) break; /* done */
        if (fwrite(buf, 1, fetched, out) != fetched) {
            set_err(err, errsz, "Write failed");
            goto done;
        }
        dctx.received += fetched;
        if (dctx.progress && dctx.progress(dctx.ud, dctx.received, dctx.total)) {
            set_err(err, errsz, "cancelled");
            fclose(out);
            out = NULL;
            DeleteFileA(dest_path);
            goto done;
        }
    }

    if (fflush(out) != 0) { set_err(err, errsz, "Flush failed"); goto done; }
    if (dctx.received == 0) { set_err(err, errsz, "Downloaded file is empty"); goto done; }
    rc = 0;

done:
    if (out) fclose(out);
    if (rc != 0 && out) DeleteFileA(dest_path);
    if (request) WinHttpCloseHandle(request);
    if (connect) WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return rc;
}

#endif /* _WIN32 */
