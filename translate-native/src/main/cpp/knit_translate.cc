#include "knit_translate.hh"

#include <exception>
#include <memory>
#include <utility>
#include <vector>

#include "internal/lang_script.h"
#include "public/compact_lang_det.h"
#include "public/encodings.h"
#include "slimt/slimt.hh"

namespace knit_translate {

struct Engine {
  std::shared_ptr<slimt::Model> model;
  slimt::Blocking service;
};

Engine *load(const std::string &model, const std::string &vocabulary, const std::string &shortlist,
             size_t encoder_layers, size_t decoder_layers, std::string *error) {
  try {
    slimt::Model::Config config;
    config.encoder_layers = encoder_layers;
    config.decoder_layers = decoder_layers;
    slimt::Package<std::string> package;
    package.model = model;
    package.vocabulary = vocabulary;
    package.shortlist = shortlist;
    auto loaded = std::make_shared<slimt::Model>(config, package);
    slimt::Config service;
    service.workers = 1;
    return new Engine{std::move(loaded), slimt::Blocking(service)};
  } catch (const std::exception &e) {
    if (error != nullptr) *error = e.what();
    return nullptr;
  }
}

std::string translate(Engine &engine, const std::string &text) {
  slimt::Options options{};
  std::vector<slimt::Response> responses = engine.service.translate(engine.model, {text}, options);
  return responses.empty() ? std::string() : responses.front().target.text;
}

void release(Engine *engine) { delete engine; }

std::string detect(const std::string &text, const std::string &hint) {
  CLD2::Language language3[3];
  int percent3[3];
  int bytes = 0;
  bool reliable = false;
  CLD2::Language boost = hint.empty() ? CLD2::UNKNOWN_LANGUAGE : CLD2::GetLanguageFromName(hint.c_str());
  CLD2::ExtDetectLanguageSummary(text.data(), static_cast<int>(text.size()), true, nullptr, CLD2::UNKNOWN_ENCODING,
                                 boost, language3, percent3, &bytes, &reliable);
  std::string out;
  for (int i = 0; i < 3; ++i) {
    if (i > 0) out += ' ';
    out += CLD2::LanguageCode(language3[i]);
    out += ':';
    out += std::to_string(percent3[i]);
  }
  out += reliable ? "|1|" : "|0|";
  out += std::to_string(bytes);
  return out;
}

}  // namespace knit_translate
