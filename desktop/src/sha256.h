/* sha256.h — self-contained streaming SHA-256 (FIPS 180-4).
 *
 * Used to verify downloaded GGUF models against the manifest checksums. */
#ifndef AXIS_SHA256_H
#define AXIS_SHA256_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct axis_sha256 {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t buffer[64];
    size_t buflen;
} axis_sha256;

void axis_sha256_init(axis_sha256 *ctx);
void axis_sha256_update(axis_sha256 *ctx, const void *data, size_t len);
/* Finalizes and writes 32 bytes (big-endian digest). */
void axis_sha256_final(axis_sha256 *ctx, uint8_t out[32]);

/* Convenience: hex digest (lowercase, 64 chars + NUL) of a file. Returns 0
 * on success. Streaming with a 64KB buffer. */
int axis_sha256_hex_file(const char *path, char out_hex[65]);

/* Convenience: hex digest of a memory buffer. */
void axis_sha256_hex_buf(const void *data, size_t len, char out_hex[65]);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_SHA256_H */
