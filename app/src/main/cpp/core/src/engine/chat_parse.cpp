#include "chat_parse.h"
#include "common.h"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <exception>
#include <mutex>

namespace bmoe::detail {

common_chat_parser_params build_parse_params(const common_chat_params & cp) {
    common_chat_parser_params pp(cp);
    // The converting constructor drops the grammar; load it so the parser can actually match.
    pp.parser.load(cp.parser);
    pp.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
    return pp;
}

void warn_parse_failed_once(const char * what) {
    static std::once_flag flag;
    std::call_once(flag, [what] {
        std::fprintf(stderr, "bmoe: reasoning parse failed (%s); showing raw model output\n", what ? what : "unknown");
    });
}

bool parsed_nothing(const common_chat_msg & msg) {
    return msg.content.empty() && msg.reasoning_content.empty() && msg.tool_calls.empty();
}

bool validate_json_response(const common_chat_msg & msg, std::string & err) {
    err.clear();
    // A tool-only message has no assistant JSON body to validate. Tool argument schemas are handled
    // by the model template's grammar; this check is for the completed textual response.
    if (msg.content.empty() && !msg.tool_calls.empty()) return true;
    if (nlohmann::ordered_json::accept(msg.content)) return true;
    err = "the completed assistant content is not valid JSON";
    return false;
}

ToolGrammar::~ToolGrammar() {
    if (smpl_) llama_sampler_free(smpl_);
}

bool ToolGrammar::init(const common_chat_params & cp, const llama_vocab * vocab, std::string & err) {
    if (cp.grammar.empty() || !vocab) return true;
    // llguidance grammars require a build option this target does not enable.
    if (cp.grammar.compare(0, 11, "%llguidance") == 0) {
        err = "the template's grammar needs llguidance";
        return false;
    }
    std::vector<std::string> patterns;
    std::vector<llama_token> trigger_tokens;
    for (const common_grammar_trigger & t : cp.grammar_triggers) {
        switch (t.type) {
        case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:
            patterns.push_back(regex_escape(t.value));
            break;
        case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
            patterns.push_back(t.value);
            break;
        case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL: {
            const std::string & v = t.value;
            patterns.push_back(v.empty() ? "^$" : (v.front() != '^' ? "^" : "") + v + (v.back() != '$' ? "$" : ""));
            break;
        }
        case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
            if (t.token != LLAMA_TOKEN_NULL) trigger_tokens.push_back(t.token);
            break;
        }
    }
    if (cp.grammar_lazy && patterns.empty() && trigger_tokens.empty()) {
        err = "the template's lazy grammar has no trigger";
        return false;
    }
    std::vector<const char *> c_patterns;
    c_patterns.reserve(patterns.size());
    for (const std::string & p : patterns) c_patterns.push_back(p.c_str());
    smpl_ = cp.grammar_lazy
        ? llama_sampler_init_grammar_lazy_patterns(vocab, cp.grammar.c_str(), "root", c_patterns.data(),
                                                   c_patterns.size(), trigger_tokens.data(), trigger_tokens.size())
        : llama_sampler_init_grammar(vocab, cp.grammar.c_str(), "root");
    if (!smpl_) {
        err = "the template's grammar does not parse";
        return false;
    }
    vocab_ = vocab;
    if (cp.grammar_lazy) {
        think_start_ = cp.thinking_start_tag;
        think_ends_ = cp.thinking_end_tags;
    }
    if (!think_start_.empty() && !think_ends_.empty()) {
        std::string gp = cp.generation_prompt;
        while (!gp.empty() && std::isspace((unsigned char) gp.back())) gp.pop_back();
        thinking_ = gp.size() >= think_start_.size() &&
                    gp.compare(gp.size() - think_start_.size(), think_start_.size(), think_start_) == 0;
    }
    if (!cp.grammar_lazy && !cp.generation_prompt.empty()) {
        const std::vector<llama_token> prefix = common_tokenize(vocab, cp.generation_prompt, false, true);
        for (size_t i = 0; i < prefix.size() && smpl_; ++i) {
            const std::string piece = common_token_to_piece(vocab, prefix[i], true);
            if (i == 0 && !piece.empty() && std::isspace((unsigned char) piece[0]) &&
                !std::isspace((unsigned char) cp.generation_prompt[0])) continue;
            accept(prefix[i]);
        }
        if (!smpl_) {
            err = "the template's grammar refuses its own generation prompt";
            return false;
        }
    }
    return true;
}

bool ToolGrammar::allows(llama_token tok) const {
    if (!smpl_ || thinking_) return true;
    llama_token_data one = {tok, 1.0f, 0.0f};
    llama_token_data_array cur = {&one, 1, -1, false};
    llama_sampler_apply(smpl_, &cur);
    return std::isfinite(one.logit);
}

void ToolGrammar::constrain(llama_token_data_array * cur) const {
    if (smpl_ && !thinking_) llama_sampler_apply(smpl_, cur);
}

void ToolGrammar::accept(llama_token tok) {
    if (!smpl_) return;
    if (!think_start_.empty() && !think_ends_.empty() && vocab_) {
        const std::string piece = common_token_to_piece(vocab_, tok, true);
        if (thinking_) {
            span_ += piece;
            for (const std::string & end : think_ends_) {
                if (!end.empty() && span_.find(end) != std::string::npos) {
                    thinking_ = false;
                    span_.clear();
                    break;
                }
            }
            return;
        }
        if (!started_) {
            span_ += piece;
            size_t lead = 0;
            while (lead < span_.size() && std::isspace((unsigned char) span_[lead])) ++lead;
            const std::string head = span_.substr(lead);
            if (head.compare(0, think_start_.size(), think_start_) == 0) {
                thinking_ = true;
                span_ = head.substr(think_start_.size());
                return;
            }
            if (head.size() >= think_start_.size() || think_start_.compare(0, head.size(), head) != 0) {
                started_ = true;
                span_.clear();
            }
        }
    }
    try {
        llama_sampler_accept(smpl_, tok);
    } catch (const std::exception & e) {
        off(e.what());
    }
}

void ToolGrammar::off(const char * why) {
    std::fprintf(stderr, "bmoe: output grammar switched off for this turn (%s)\n", why ? why : "unknown");
    llama_sampler_free(smpl_);
    smpl_ = nullptr;
}

} // namespace bmoe::detail
