/* prompt.h — instruction prompt builder (parity with the Android app's
 * InstructionPromptBuilder). */
#ifndef AXIS_PROMPT_H
#define AXIS_PROMPT_H

#include "str.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    AXIS_STYLE_STANDARD = 0,
    AXIS_STYLE_NATURAL = 1,
    AXIS_STYLE_FORMAL = 2,
    AXIS_STYLE_CASUAL = 3,
} axis_style_t;

const char *axis_style_name(axis_style_t style);

/* Builds the plain instruction prompt. source_name may be the auto-detect
 * result. The engine layer wraps this with the model's chat template. */
void axis_build_translation_prompt(axis_str *out,
                                   const char *source_name,
                                   const char *target_name,
                                   const char *text,
                                   axis_style_t style);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_PROMPT_H */
