// JNI for app.getknit.knit.ui.translate.TranslateJni (ADR 2026-10.t2h6). Text goes both ways as UTF-8 byte arrays:
// GetStringUTFChars hands out modified UTF-8, which writes an emoji as two surrogates no tokenizer reads.
#include <jni.h>

#include <string>

#include "knit_translate.hh"

namespace {

std::string chars(JNIEnv *env, jstring value) {
  const char *raw = env->GetStringUTFChars(value, nullptr);
  std::string out(raw);
  env->ReleaseStringUTFChars(value, raw);
  return out;
}

std::string bytes(JNIEnv *env, jbyteArray value) {
  jsize length = env->GetArrayLength(value);
  std::string out(static_cast<size_t>(length), '\0');
  env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(out.data()));
  return out;
}

jbyteArray array(JNIEnv *env, const std::string &value) {
  jbyteArray out = env->NewByteArray(static_cast<jsize>(value.size()));
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_getknit_knit_ui_translate_TranslateJni_nativeLoad(
    JNIEnv *env, jclass, jstring model, jstring vocabulary, jstring shortlist, jint encoder_layers,
    jint decoder_layers) {
  std::string error;
  knit_translate::Engine *engine =
      knit_translate::load(chars(env, model), chars(env, vocabulary), chars(env, shortlist),
                           static_cast<size_t>(encoder_layers), static_cast<size_t>(decoder_layers), &error);
  return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jbyteArray JNICALL Java_app_getknit_knit_ui_translate_TranslateJni_nativeTranslate(JNIEnv *env, jclass,
                                                                                          jlong handle,
                                                                                          jbyteArray text) {
  auto *engine = reinterpret_cast<knit_translate::Engine *>(handle);
  if (engine == nullptr) return nullptr;
  return array(env, knit_translate::translate(*engine, bytes(env, text)));
}

JNIEXPORT void JNICALL Java_app_getknit_knit_ui_translate_TranslateJni_nativeFree(JNIEnv *, jclass, jlong handle) {
  knit_translate::release(reinterpret_cast<knit_translate::Engine *>(handle));
}

JNIEXPORT jstring JNICALL Java_app_getknit_knit_ui_translate_TranslateJni_nativeDetect(JNIEnv *env, jclass,
                                                                                    jbyteArray text) {
  return env->NewStringUTF(knit_translate::detect(bytes(env, text)).c_str());
}

}  // extern "C"
