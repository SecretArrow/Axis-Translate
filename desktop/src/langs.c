/* langs.c — language catalog mirroring the manifest languages. */
#include "langs.h"
#include "str.h"

#include <string.h>

typedef struct {
    const char *code;
    const char *name;
} axis_lang;

static const axis_lang kLangs[] = {
    { "en", "English" },
    { "id", "Indonesian" },
    { "ja", "Japanese" },
    { "ko", "Korean" },
    { "zh", "Chinese (Simplified)" },
    { "es", "Spanish" },
    { "fr", "French" },
    { "de", "German" },
    { "it", "Italian" },
    { "pt", "Portuguese" },
    { "ru", "Russian" },
    { "ar", "Arabic" },
    { "hi", "Hindi" },
    { "th", "Thai" },
    { "vi", "Vietnamese" },
    { "tr", "Turkish" },
    { "nl", "Dutch" },
    { "pl", "Polish" },
    { "ms", "Malay" },
};

int axis_langs_count(void)
{
    return (int)(sizeof(kLangs) / sizeof(kLangs[0]));
}

const char *axis_lang_name(int idx)
{
    if (idx < 0 || idx >= axis_langs_count()) return "Auto";
    return kLangs[idx].name;
}

const char *axis_lang_code(int idx)
{
    if (idx < 0 || idx >= axis_langs_count()) return "auto";
    return kLangs[idx].code;
}

int axis_lang_index(const char *code)
{
    if (!code || !code[0] || axis_str_ieq(code, "auto")) return AXIS_LANG_AUTO;
    for (int i = 0; i < axis_langs_count(); i++) {
        if (axis_str_ieq(code, kLangs[i].code)) return i;
    }
    return -1;
}

const char *axis_lang_auto_name(void)
{
    return "Auto (detect)";
}
