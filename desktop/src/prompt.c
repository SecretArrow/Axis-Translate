/* prompt.c — instruction prompt builder.
 *
 * Byte-for-byte parity with the Android app's InstructionPromptBuilder:
 * role line, style line, output contract, payload in triple quotes. */
#include "prompt.h"

const char *axis_style_name(axis_style_t style)
{
    switch (style) {
        case AXIS_STYLE_NATURAL: return "Natural";
        case AXIS_STYLE_FORMAL: return "Formal";
        case AXIS_STYLE_CASUAL: return "Casual";
        case AXIS_STYLE_STANDARD:
        default: return "Standard";
    }
}

void axis_build_translation_prompt(axis_str *out,
                                   const char *source_name,
                                   const char *target_name,
                                   const char *text,
                                   axis_style_t style)
{
    axis_str_clear(out);

    axis_str_appendf(out, "You are a professional translation engine. "
                          "Translate the text from %s to %s.",
                     source_name ? source_name : "the auto-detected language",
                     target_name ? target_name : "English");

    switch (style) {
        case AXIS_STYLE_NATURAL:
            axis_str_append(out, "\nPrefer natural, idiomatic phrasing.");
            break;
        case AXIS_STYLE_FORMAL:
            axis_str_append(out, "\nUse a formal, professional register.");
            break;
        case AXIS_STYLE_CASUAL:
            axis_str_append(out, "\nUse a casual, conversational register.");
            break;
        case AXIS_STYLE_STANDARD:
        default:
            break;
    }

    axis_str_append(out, "\nOutput ONLY the translated text, no quotes, no explanations.");
    axis_str_append(out, "\nText:");
    axis_str_append(out, "\n\"\"\"\n");
    axis_str_append(out, text);
    axis_str_append(out, "\n\"\"\"");
}
