/* test_main.c — assert-based unit tests for the pure-C logic modules.
 *
 * Exits non-zero on the first failure. No GUI, no llama.cpp — CI runs this
 * on both Windows and Linux. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

#include "chunker.h"
#include "fs.h"
#include "langs.h"
#include "manifest.h"
#include "models.h"
#include "prompt.h"
#include "sha256.h"
#include "store.h"
#include "str.h"

/* download.c is not linked into the test binary — stub it (models.c
 * references it, but no test calls it). */
#include "download.h"
int axis_download(const char *url, const char *dest_path,
                  axis_dl_progress progress, void *ud,
                  char *err, size_t errsz)
{
    (void)url; (void)dest_path; (void)progress; (void)ud; (void)err; (void)errsz;
    return -1;
}

static int failures = 0;

#define CHECK(cond)                                                         \
    do {                                                                    \
        if (!(cond)) {                                                      \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            failures++;                                                     \
        }                                                                   \
    } while (0)

#define CHECK_STR(a, b)                                                     \
    do {                                                                    \
        if (strcmp((a), (b)) != 0) {                                         \
            fprintf(stderr, "FAIL %s:%d: \"%s\" != \"%s\"\n",               \
                    __FILE__, __LINE__, (a), (b));                          \
            failures++;                                                     \
        }                                                                   \
    } while (0)

/* --------------------------------------------------------------- str ---- */

static void test_str(void)
{
    axis_str s;
    axis_str_init(&s);
    axis_str_append(&s, "Hello");
    axis_str_append(&s, " ");
    axis_str_append(&s, "World");
    CHECK_STR(s.data, "Hello World");
    CHECK(s.len == 11);

    axis_str_clear(&s);
    axis_str_appendf(&s, "%d-%s-%.2f", 42, "x", 1.5);
    CHECK_STR(s.data, "42-x-1.50");

    axis_str_clear(&s);
    axis_str_append(&s, "dir");
    axis_str_path_join(&s, "sub");
#ifdef _WIN32
    CHECK_STR(s.data, "dir\\sub");
#else
    CHECK_STR(s.data, "dir/sub");
#endif

    /* utf8 truncation must not split a sequence */
    axis_str_clear(&s);
    axis_str_append(&s, "\xE6\x97\xA5\xE6\x9C\xAC\xE8\xAA\x9E"); /* 3x3 bytes */
    axis_utf8_truncate(&s, 7);
    CHECK(s.len == 6);
    CHECK(memcmp(s.data, "\xE6\x97\xA5\xE6\x9C\xAC", 6) == 0);
    axis_str_free(&s);

    CHECK(axis_str_ieq("EN", "en"));
    CHECK(axis_str_ieq("", ""));
    CHECK(!axis_str_ieq("en", "id"));
}

/* ------------------------------------------------------------- sha256 ---- */

static void test_sha256(void)
{
    char hex[65];

    axis_sha256_hex_buf("", 0, hex);
    CHECK_STR(hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    axis_sha256_hex_buf("abc", 3, hex);
    CHECK_STR(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

    axis_sha256_hex_buf("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq", 56, hex);
    CHECK_STR(hex, "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");

    /* streaming updates == one-shot */
    axis_sha256 ctx;
    uint8_t digest[32];
    axis_sha256_init(&ctx);
    axis_sha256_update(&ctx, "abcdbcdecdef", 12);
    axis_sha256_update(&ctx, "defgefghfghighijhijkijkljklmklmnlmnomnopnopq", 44);
    axis_sha256_final(&ctx, digest);
    for (int i = 0; i < 32; i++) sprintf(hex + i * 2, "%02x", digest[i]);
    CHECK_STR(hex, "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");

    /* block boundary: 64 bytes exactly */
    axis_sha256_hex_buf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        64, hex);
    CHECK_STR(hex, "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb");
}

/* -------------------------------------------------------------- langs ---- */

static void test_langs(void)
{
    CHECK(axis_langs_count() == 32);
    CHECK_STR(axis_lang_code(0), "en");
    CHECK_STR(axis_lang_name(1), "Indonesian");
    CHECK(axis_lang_index("JA") == 6);
    CHECK(axis_lang_index("auto") == AXIS_LANG_AUTO);
    CHECK(axis_lang_index("xx") == -1);
}

/* ------------------------------------------------------------ chunker ---- */

static void test_chunker(void)
{
    axis_span out[AXIS_CHUNK_MAX];

    int n = axis_chunk_text("Hello world", 11, out, AXIS_CHUNK_MAX);
    CHECK(n == 1);
    CHECK(out[0].len == 11);

    n = axis_chunk_text("First para.\n\nSecond para.", 25, out, AXIS_CHUNK_MAX);
    CHECK(n == 2);
    CHECK(out[0].len == 11);
    CHECK(out[1].len == 12);

    /* long paragraph gets split at sentence ends */
    char big[2600];
    size_t pos = 0;
    for (int i = 0; i < 80; i++) {
        pos += (size_t)snprintf(big + pos, sizeof(big) - pos, "Sentence %d goes here. ", i);
    }
    n = axis_chunk_text(big, pos, out, AXIS_CHUNK_MAX);
    CHECK(n >= 2);
    size_t total = 0;
    for (int i = 0; i < n; i++) {
        CHECK(out[i].len > 0);
        CHECK(out[i].len <= 1832);
        total += out[i].len;
    }
    CHECK(total <= pos);

    /* script detection */
    CHECK(axis_detect_language("Hello the and is of to", 22) >= 0); /* English-ish */
    CHECK(axis_detect_language("این یک متن فارسی است", 36) == axis_lang_index("ar"));
    CHECK(axis_detect_language("これは日本語のテキストです", 33) == axis_lang_index("ja"));
    CHECK(axis_detect_language("这是中文文本", 16) == axis_lang_index("zh"));
}

/* ------------------------------------------------------------- prompt ---- */

static void test_prompt(void)
{
    axis_str p;
    axis_str_init_cap(&p, 128);

    axis_build_translation_prompt(&p, "English", "Indonesian",
                                  "Good morning", AXIS_STYLE_STANDARD);
    const char *expected =
        "You are a professional translation engine. "
        "Translate the text from English to Indonesian."
        "\nOutput ONLY the translated text, no quotes, no explanations."
        "\nText:"
        "\n\"\"\"\nGood morning\n\"\"\"";
    CHECK_STR(p.data, expected);

    axis_str_clear(&p);
    axis_build_translation_prompt(&p, "English", "Japanese",
                                  "Hi", AXIS_STYLE_FORMAL);
    CHECK(strstr(p.data, "Use a formal, professional register.") != NULL);

    axis_str_clear(&p);
    axis_build_translation_prompt(&p, "English", "Indonesian", "Yo",
                                 AXIS_STYLE_CASUAL);
    CHECK(strstr(p.data, "Use a casual, conversational register.") != NULL);

    axis_str_clear(&p);
    axis_build_translation_prompt(&p, "English", "Indonesian", "Hey",
                                 AXIS_STYLE_NATURAL);
    CHECK(strstr(p.data, "Prefer natural, idiomatic phrasing.") != NULL);

    axis_str_free(&p);
}

/* ----------------------------------------------------------- manifest ---- */

static void test_manifest(void)
{
    axis_manifest m;
    CHECK(axis_manifest_load_embedded(&m) == 0);
    CHECK(m.count == 3);

    const axis_manifest_entry *def = axis_manifest_default(&m);
    CHECK(def != NULL);
    CHECK_STR(def->id, "qwen3.5-2b");
    CHECK(def->size_bytes == 1280835840ULL);
    CHECK(def->is_default == 1);
    CHECK(def->lang_count > 0);
    CHECK_STR(def->url,
              "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf");
    CHECK_STR(def->sha256, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223");

    const axis_manifest_entry *big = axis_manifest_by_id(&m, "qwen3.5-0.8b");
    CHECK(big != NULL);
    CHECK(big->size_bytes == 532517120ULL);

    const axis_manifest_entry *fallback = axis_manifest_by_id(&m, "qwen2.5-0.5b-instruct");
    CHECK(fallback != NULL);
    CHECK(fallback->is_default == 0);

    /* placeholder checksum handling */
    CHECK(axis_manifest_sha_known("0000000000000000000000000000000000000000000000000000000000000000") == 0);
    CHECK(axis_manifest_sha_known("74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db") == 1);
    CHECK(axis_manifest_sha_known("") == 0);

    /* robust against garbage */
    axis_manifest bad;
    CHECK(axis_manifest_parse(&bad, "{not json", 9) == -1);
    CHECK(axis_manifest_parse(&bad, "{\"models\":[]}", 13) == -1);
}

/* --------------------------------------------------------------- store ---- */

static void test_store(void)
{
    char dir[128];
#ifdef _WIN32
    snprintf(dir, sizeof(dir), "test-tmp-%d", (int)GetTickCount());
#else
    snprintf(dir, sizeof(dir), "test-tmp-%d", (int)getpid());
#endif
    CHECK(axis_fs_mkdir_p(dir) == 0);

    /* config roundtrip */
    axis_config cfg;
    axis_config_defaults(&cfg);
    cfg.threads = 8;
    cfg.temperature = 0.35f;
    cfg.max_output_tokens = 1024;
    cfg.style = AXIS_STYLE_FORMAL;
    cfg.source_lang = axis_lang_index("ja");
    cfg.target_lang = axis_lang_index("en");
    cfg.dark_theme = 0;
    CHECK(axis_store_save_config(dir, &cfg) == 0);

    axis_config loaded;
    CHECK(axis_store_load_config(dir, &loaded) == 0);
    CHECK(loaded.threads == 8);
    CHECK(loaded.temperature > 0.34f && loaded.temperature < 0.36f);
    CHECK(loaded.max_output_tokens == 1024);
    CHECK(loaded.style == AXIS_STYLE_FORMAL);
    CHECK(loaded.source_lang == axis_lang_index("ja"));
    CHECK(loaded.target_lang == axis_lang_index("en"));
    CHECK(loaded.dark_theme == 0);

    /* history roundtrip */
    axis_history h;
    memset(&h, 0, sizeof(h));
    CHECK(axis_store_add_history(dir, &h, "ja", "en",
                                 "こんにちは", "Hello") == 0);
    CHECK(h.count == 1);

    axis_history h2;
    CHECK(axis_store_load_history(dir, &h2) == 0);
    CHECK(h2.count == 1);
    CHECK_STR(h2.items[0].input, "こんにちは");
    CHECK_STR(h2.items[0].output, "Hello");
    CHECK_STR(h2.items[0].source_lang, "ja");

    CHECK(axis_store_clear_history(dir, &h2) == 0);
    CHECK(h2.count == 0);

    axis_fs_remove_tree(dir);
}

/* ----------------------------------------------------------------- fs ---- */

static void test_fs(void)
{
    char path[128];
#ifdef _WIN32
    snprintf(path, sizeof(path), "test-fs-%d.gguf", (int)GetTickCount());
#else
    snprintf(path, sizeof(path), "test-fs-%d.gguf", (int)getpid());
#endif
    FILE *f = fopen(path, "wb");
    CHECK(f != NULL);
    if (f) {
        fwrite("GGUF", 1, 4, f);
        fwrite("junkjunkjunk", 1, 12, f);
        fclose(f);
    }
    CHECK(axis_fs_is_gguf(path) == 1);
    CHECK(axis_fs_file_size(path) == 16);

    f = fopen(path, "wb");
    if (f) { fwrite("NOPE", 1, 4, f); fclose(f); }
    CHECK(axis_fs_is_gguf(path) == 0);

    char *data = axis_fs_read_all(path, NULL);
    CHECK(data != NULL);
    if (data) CHECK_STR(data, "NOPE");
    free(data);

    CHECK(axis_fs_remove_file(path) == 0);
    CHECK(axis_fs_exists(path) == 0);

    CHECK_STR(axis_fs_base_name("/home/x/model.gguf"), "model.gguf");
    CHECK_STR(axis_fs_base_name("C:\\Users\\model.gguf"), "model.gguf");
}

/* -------------------------------------------------------------- models ---- */

static void test_models(void)
{
    char out[32];
    axis_models_format_bytes(0, out, sizeof(out));
    CHECK_STR(out, "0 B");
    axis_models_format_bytes(1536, out, sizeof(out));
    CHECK_STR(out, "1.5 KB");
    axis_models_format_bytes(2 * 1024 * 1024, out, sizeof(out));
    CHECK_STR(out, "2.0 MB");
    axis_models_format_bytes(1536ULL * 1024 * 1024 * 1024, out, sizeof(out));
    CHECK(strstr(out, "TB") != NULL);
}

int main(void)
{
    test_str();
    test_sha256();
    test_langs();
    test_chunker();
    test_prompt();
    test_manifest();
    test_store();
    test_fs();
    test_models();

    if (failures) {
        fprintf(stderr, "axis-tests: %d failure(s)\n", failures);
        return 1;
    }
    printf("axis-tests: all tests passed\n");
    return 0;
}
