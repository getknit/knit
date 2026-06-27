# On-device content-moderation assets

Everything here is loaded locally at runtime — the app has **no `INTERNET` permission**, so models must
be **bundled**, never downloaded.

## `profanity_en.txt` (present)

Word list for the deterministic first pass of text moderation (`LexicalTextFilter`). A small starter
set — replace/extend with a vetted, permissively-licensed corpus (e.g. LDNOOBW) before release.

## `nsfw.tflite` (bundled — activates image moderation)

The NSFW image classifier consumed by `NsfwImageModerator`. Currently the **GantMan `nsfw_model`
MobileNetV2 (140/224)**: input `[1,224,224,3]` float32 normalized `÷255`, softmax output `[1,5]` =
`drawings, hentai, neutral, porn, sexy`; the moderator flags on `hentai+porn+sexy ≥ 0.7`. ~17 MB float
model, **managed via Git LFS** (`.gitattributes` tracks `*.tflite`).

Shapes and input dtype are read from the model at load, so swapping in a compatible model "just works":
a MobileNet-style classifier with `[1,H,W,3]` input (float32 `[0,1]` or uint8 `0..255`) and `[1,N]`
output. A quantized `.tflite` (~4–5 MB) drops in unchanged. To use a model with a different class
layout, adjust `unsafeClasses`/`threshold` in `NsfwImageModerator`. If the file is **absent or fails to
load, the moderator degrades to allow-all** (hooks + blur UI stay wired).

**Vet the model's license** for redistribution in the APK before release, and tune the threshold
on-device. `*.tflite` is kept uncompressed in the APK (`androidResources { noCompress }` in
`app/build.gradle.kts`) so TFLite can mmap it.

## `toxicity.tflite` + `tokenizer.json` (bundled — activates text ML moderation)

The on-device toxicity classifier consumed by `MlTextModerator`, layered into `HybridTextModerator`
after the lexical pass (it only sees text the word list clears). **Detoxify "original-small" (ALBERT)**
fine-tuned on the Jigsaw Toxic Comment Challenge, exported to TFLite: inputs `input_ids` /
`attention_mask` `[1, 128]` (int), output `[1, 6]` sigmoid probabilities
(`toxic, severe_toxic, obscene, threat, insult, identity_hate`); flagged when the max label probability
≥ 0.7. ~14.6 MB dynamic-int8 model, **managed via Git LFS** (`.gitattributes` tracks `*.tflite`).

`tokenizer.json` is the HuggingFace tokenizer, read on-device by `ai.djl.huggingface:tokenizers`
(offline, no network) — the same tokenizer used in training, so token ids match exactly. If either file
is **absent or fails to load, `MlTextModerator` degrades to allow-all** and the lexical pass still runs.

Both files are produced (and re-tunable) by the separate `detoxify-mobile` conversion pipeline; license
is Apache-2.0 (Detoxify weights + albert-base-v2) — retain attribution when shipping. Full design and
wiring: [`docs/CONTENT_MODERATION.md`](../../../../../docs/CONTENT_MODERATION.md) §4.
