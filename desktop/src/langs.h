/* langs.h — language catalog (mirrors the model manifest languages). */
#ifndef AXIS_LANGS_H
#define AXIS_LANGS_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Special source selection: auto-detect. */
#define AXIS_LANG_AUTO (-1)

/* Number of real languages (excluding AUTO). */
int axis_langs_count(void);

/* Display name, e.g. axis_lang_name(2) == "Japanese". */
const char *axis_lang_name(int idx);

/* ISO code, e.g. axis_lang_code(2) == "ja". */
const char *axis_lang_code(int idx);

/* Index for a code (AXIS_LANG_AUTO for "auto"), or -1 when unknown. */
int axis_lang_index(const char *code);

/* Display name for AUTO. */
const char *axis_lang_auto_name(void);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_LANGS_H */
