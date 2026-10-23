// Desktop bench for the translation spike (ADR 2026-10.t2h6): the phone's native core over a corpus, one message
// per line, so the phone's output can be compared with a reference from the same code.
//
//   knit-translate-bench translate <model> <vocab> <shortlist> <decoder-layers> <corpus>
//       prints "<line>\t<ms>\t<translation>" per line, and the load time on stderr
//   knit-translate-bench detect <corpus> [hint]
//       prints "<line>\t<CLD2 answer>" per line, with CLD2 boosting [hint] (a language code) when given
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>

#include "../src/main/cpp/knit_translate.hh"

namespace {

double since(std::chrono::steady_clock::time_point start) {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

int usage() {
  std::fprintf(stderr,
               "usage: knit-translate-bench translate <model> <vocab> <shortlist> <decoder-layers> <corpus>\n"
               "       knit-translate-bench detect <corpus> [hint]\n");
  return 2;
}

}  // namespace

int main(int argc, char **argv) {
  if (argc < 3) return usage();
  std::string mode = argv[1];
  if (mode == "detect" && (argc == 3 || argc == 4)) {
    std::ifstream corpus(argv[2]);
    std::string hint = argc == 4 ? argv[3] : "";
    std::string line;
    for (int n = 1; std::getline(corpus, line); ++n) {
      std::printf("%d\t%s\n", n, knit_translate::detect(line, hint).c_str());
    }
    return 0;
  }
  if (mode != "translate" || argc != 7) return usage();
  auto start = std::chrono::steady_clock::now();
  std::string error;
  knit_translate::Engine *engine =
      knit_translate::load(argv[2], argv[3], argv[4], 6, std::strtoul(argv[5], nullptr, 10), &error);
  if (engine == nullptr) {
    std::fprintf(stderr, "load failed: %s\n", error.c_str());
    return 1;
  }
  std::fprintf(stderr, "load_ms\t%.1f\n", since(start));
  std::ifstream corpus(argv[6]);
  std::string line;
  for (int n = 1; std::getline(corpus, line); ++n) {
    auto begin = std::chrono::steady_clock::now();
    std::string out = knit_translate::translate(*engine, line);
    std::printf("%d\t%.1f\t%s\n", n, since(begin), out.c_str());
  }
  knit_translate::release(engine);
  return 0;
}
