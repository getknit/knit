// The translation spike's native core (ADR 2026-10.t2h6): slimt for the Marian models and CLD2 for the language.
// The JNI and the desktop bench both call these, so the bench's reference translations come from the code the
// phone runs. Text crosses this boundary as real UTF-8 bytes, never JNI's modified UTF-8, which splits an emoji.
#pragma once

#include <cstddef>
#include <string>

namespace knit_translate {

// One direction's model, loaded, with the blocking service that runs it. Not thread-safe: one call at a time.
struct Engine;

// Loads the three files of one direction. Null and [error] set when slimt refuses them.
Engine *load(const std::string &model, const std::string &vocabulary, const std::string &shortlist,
             size_t encoder_layers, size_t decoder_layers, std::string *error);

// [text] translated, as UTF-8.
std::string translate(Engine &engine, const std::string &text);

void release(Engine *engine);

// CLD2's answer for [text]: the top three as "code:percent" separated by spaces, then "|1" or "|0" for its
// reliable bit and "|" the bytes it scored, e.g. "es:97 un:0 un:0|1|38". [hint], a language code or empty, is the
// language the author or the chat usually writes in: CLD2 boosts it, which tips a close call without overruling text
// that is plainly something else.
std::string detect(const std::string &text, const std::string &hint);

}  // namespace knit_translate
