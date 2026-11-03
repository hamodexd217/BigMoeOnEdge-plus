#pragma once

// Wiring for llama.cpp's reasoning parser. Kept separate from session.cpp so the seam that
// was silently broken (issue #49) is unit-testable without a model or a live session.
//
// Internal header: includes llama.cpp's `common` (not the stable public API), so it must not
// be pulled into core/include/bmoe/. See docs/seam.md.

#include "chat.h"
#include "llama.h"

#include <string>
#include <vector>

namespace bmoe::detail {

// Build reasoning-parser params from an applied chat template.
//
// The `common_chat_parser_params(const common_chat_params &)` convenience constructor copies
// only `format` and `generation_prompt` (chat.h) — it leaves the PEG `parser` arena empty, so
// `common_chat_parse` throws on the first token and reasoning markers leak into the answer.
// The serialized grammar has to be loaded explicitly (this is what the upstream server does).
common_chat_parser_params build_parse_params(const common_chat_params & cp);

// Warn exactly once that reasoning parsing failed and the raw stream is being shown instead.
// Returning the raw text is the right degradation, but doing it silently is how #49 stayed
// invisible; the next wiring regression should be noticed.
void warn_parse_failed_once(const char * what);


// A grammar built from the model's own chat-template output. The helper supports the template's lazy
// triggers (used by tool-call templates) as well as response schemas. It is kept turn-local so one
// API request cannot leak grammar state into the next request.
class ToolGrammar {
public:
    ToolGrammar() = default;
    ToolGrammar(const ToolGrammar &) = delete;
    ToolGrammar & operator=(const ToolGrammar &) = delete;
    ~ToolGrammar();

    bool init(const common_chat_params & cp, const llama_vocab * vocab, std::string & err);
    bool active() const { return smpl_ != nullptr; }
    bool allows(llama_token tok) const;
    void constrain(llama_token_data_array * cur) const;
    void accept(llama_token tok);

private:
    void off(const char * why);
    llama_sampler * smpl_ = nullptr;
    const llama_vocab * vocab_ = nullptr;
    std::string think_start_;
    std::vector<std::string> think_ends_;
    bool thinking_ = false;
    bool started_ = false;
    std::string span_;
};

bool parsed_nothing(const common_chat_msg & msg);
bool validate_json_response(const common_chat_msg & msg, std::string & err);

} // namespace bmoe::detail
