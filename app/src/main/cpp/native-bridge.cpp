// JNI bridge between the Kotlin app and BigMoeOnEdge's bmoe::Session.
//
// API facts this file relies on (verified against core/include/bmoe/session.h and
// core/src/engine/session.cpp in this repo, see docs/bmoe_real_api_notes.md and patches/):
//   * Session::open(cfg, error) is the only way to get a session; everything in SessionConfig
//     (sampling, cache, dense weights, n_ctx, threads) is fixed for the session's lifetime.
//   * Session::generate() BLOCKS and invokes on_token synchronously on the calling thread, so the
//     JNIEnv of the calling (Kotlin IO) thread is valid inside the callback.
//   * Session::cancel() is a thread-safe atomic flag; a cancelled generate() rolls the whole turn back.
//   * TokenMetrics::text / ::reasoning are CUMULATIVE strings and may end in a partial UTF-8 sequence.
//
// Strings cross JNI as raw UTF-8 byte arrays, never as jstring: NewStringUTF/GetStringUTFChars use
// *modified* UTF-8, which mangles or aborts (CheckJNI) on 4-byte sequences such as emoji and on any
// multi-byte character split across tokens.

#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "bmoe/session.h"

#define LOG_TAG "OnEdge-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Layout of the int/float arrays passed to nativeLoadModel. MUST stay in sync with
// LoadConfig.toIntArray()/toFloatArray() in EngineTypes.kt (a unit test pins the Kotlin side).
enum IntIdx {
    I_N_CTX = 0, I_N_BATCH, I_N_UBATCH, I_N_THREADS, I_TOP_K,
    I_CACHE_MODE,      // 0 auto, 1 fixed, 2 off
    I_CACHE_MB, I_CACHE_FLOOR_MB, I_CACHE_CEIL_MB, I_IO_THREADS,
    I_DENSE_MODE,      // 0 mmap, 1 warm, 2 anon, 3 pinned
    I_N_EXPERT_USED, I_DROP_COLD_PCT, I_SUBSTITUTE_PCT, I_PREFETCH_LAYERS, I_PREDICT_PREFETCH,
    I_PREDICT_SPEC_MAX, I_ROUTE_AHEAD,
    I_SPEC_SOURCE,     // 0 off, 1 mtp, 2 ngram
    I_DRAFT_MAX, I_MTP_P_MIN_PCT, I_O_DIRECT, I_OVERLAP, I_ROW_STREAM, I_RELEASE_MMAP,
    I_MMAP_BASELINE,   // 1 = no streaming at all (llama.cpp's ordinary mmap load)
    I_NPU_PREFILL,      // 1 = optional Hexagon/NPU prefill
    I_NPU_LOADERS,      // loader threads for the prefill device
    I_COUNT
};
enum FloatIdx { F_TEMPERATURE = 0, F_TOP_P, F_COUNT };

namespace {

// Guards g_session (the pointer), never held while generating.
std::mutex g_session_mu;
std::shared_ptr<bmoe::Session> g_session;
// Held for the whole duration of a generate() call. unload/reload takes it (after cancel()) so the
// session is never destroyed underneath a running generation.
std::mutex g_generate_mu;
std::string g_model_info;
// Optional per-token CSV writer (Settings -> Metrics CSV). Owned here, lives as long as the session.
std::unique_ptr<bmoe::IMetricsSink> g_csv_sink;

std::shared_ptr<bmoe::Session> current_session() {
    std::lock_guard<std::mutex> lk(g_session_mu);
    return g_session;
}

std::string from_bytes(JNIEnv * env, jbyteArray arr) {
    if (arr == nullptr) return std::string();
    const jsize n = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr != nullptr && !s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

// Length of the longest prefix of `s` that does not end inside a multi-byte UTF-8 sequence.
// Bytes after that prefix are held back until the following token completes the character.
size_t utf8_complete_len(const std::string & s) {
    const size_t n = s.size();
    size_t i = n;
    for (int back = 0; i > 0 && back < 4; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { // continuation byte: keep looking for its lead byte
            --i;
            continue;
        }
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        const size_t have = n - (i - 1);
        return have < need ? i - 1 : n;
    }
    return n; // malformed (only continuation bytes): pass through, Kotlin's decoder substitutes U+FFFD
}

// If `cur` (complete-UTF-8 part) extends what was already sent, return the new suffix and advance `sent`.
// A re-parse can momentarily disagree with an earlier partial parse; text already sent cannot be
// retracted, so in that case wait until the cumulative text extends `sent` again. The authoritative
// final text is delivered in onFinished.
bool next_delta(std::string & sent, const std::string & cur, std::string & delta) {
    const size_t safe = utf8_complete_len(cur);
    if (safe <= sent.size()) return false;
    if (std::memcmp(cur.data(), sent.data(), sent.size()) != 0) return false;
    delta.assign(cur, sent.size(), safe - sent.size());
    sent.assign(cur, 0, safe);
    return true;
}

void release_session_blocking() {
    std::shared_ptr<bmoe::Session> s = current_session();
    if (s) s->cancel();
    // Wait for an in-flight generate() to unwind before dropping our reference.
    std::lock_guard<std::mutex> gen_lk(g_generate_mu);
    std::lock_guard<std::mutex> lk(g_session_mu);
    g_session.reset();
    g_csv_sink.reset();
    g_model_info.clear();
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------------------------
// Model lifecycle
// ---------------------------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeFreeModel(JNIEnv *, jobject) {
    LOGI("Releasing Session");
    release_session_blocking();
}

// Returns an EMPTY byte array on success, otherwise the UTF-8 error message.
// Every option is applied exactly as the original BigMoeOnEdge Android app's argv builder does
// (AppSettings.sessionArgv): options that need the LRU cache are only set when the cache is on, etc.
JNIEXPORT jbyteArray JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeLoadModel(
    JNIEnv * env, jobject /* thiz */,
    jbyteArray j_model_path, jintArray j_ints, jfloatArray j_floats, jbyteArray j_csv_path,
    jbyteArray j_mmproj_path)
{
    const std::string model_path = from_bytes(env, j_model_path);
    if (model_path.empty()) return to_bytes(env, "model path is empty");
    if (j_ints == nullptr || env->GetArrayLength(j_ints) < I_COUNT ||
        j_floats == nullptr || env->GetArrayLength(j_floats) < F_COUNT) {
        return to_bytes(env, "internal error: bad config array size");
    }
    jint iv[I_COUNT];
    jfloat fv[F_COUNT];
    env->GetIntArrayRegion(j_ints, 0, I_COUNT, iv);
    env->GetFloatArrayRegion(j_floats, 0, F_COUNT, fv);
    const std::string csv_path = from_bytes(env, j_csv_path);
    const std::string mmproj_path = from_bytes(env, j_mmproj_path);

    release_session_blocking();

    bmoe::SessionConfig cfg;
    cfg.model_path = model_path;
    cfg.n_ctx = std::max<int>(256, iv[I_N_CTX]);
    cfg.n_batch = std::clamp<int>(iv[I_N_BATCH], 1, cfg.n_ctx);
    cfg.n_ubatch = std::clamp<int>(iv[I_N_UBATCH], 0, cfg.n_batch);
    cfg.n_threads = std::max<int>(1, iv[I_N_THREADS]);
    cfg.chatml = true; // Session renders the model's own chat template over the whole conversation
    cfg.n_expert_used = std::max<int>(0, iv[I_N_EXPERT_USED]); // 0 = the model's own top-k
    cfg.mmproj_path = mmproj_path; // patch 0003; empty = text only
#ifdef BMOE_HAVE_HEXAGON
    if (iv[I_NPU_PREFILL] != 0 && iv[I_ROW_STREAM] == 0 && iv[I_SPEC_SOURCE] == 0) {
        cfg.prefill.device = "HTP0";
        cfg.prefill.load_threads = std::clamp<int>(iv[I_NPU_LOADERS], 1, bmoe::PrefillDeviceConfig::load_threads_max);
        cfg.prefill.routed = true;
        cfg.prefill.routed_full_frac = 0.85f; // upstream 0.28 default
    }
#endif
    cfg.decide.enabled = true;
    cfg.decide.prefix_cache = bmoe::PrefixCacheMode::Auto;

    cfg.sampling.temp = fv[F_TEMPERATURE];
    cfg.sampling.top_p = fv[F_TOP_P];
    cfg.sampling.top_k = std::max<int>(0, iv[I_TOP_K]);

    const bool streaming = iv[I_MMAP_BASELINE] == 0;
    if (streaming) {
        bmoe::MoeStreamConfig & m = cfg.moe;
        m.enabled = true;
        m.io_threads = std::clamp<int>(iv[I_IO_THREADS], 1, bmoe::MoeStreamConfig::io_threads_max);
        m.o_direct = iv[I_O_DIRECT] != 0;
        bool cache_on = true;
        switch (iv[I_CACHE_MODE]) {
            case 1: { // fixed budget
                const int mb = std::max<int>(0, iv[I_CACHE_MB]);
                m.cache_auto = false;
                m.cache_mb = mb;
                // Budgets in the pathological band are only accepted when the caller means it.
                if (mb > 0 && mb < bmoe::MoeStreamConfig::cache_min_mb) m.force_cache = true;
                cache_on = mb > 0;
                break;
            }
            case 2: // cache off: experts re-read from flash every token
                m.cache_auto = false;
                m.cache_mb = 0;
                cache_on = false;
                break;
            default: // auto: sized once from free RAM, mutually exclusive with cache_mb
                m.cache_auto = true;
                m.cache_mb = 0;
                m.cache_floor_mb = std::max<int>(0, iv[I_CACHE_FLOOR_MB]);
                m.cache_ceil_mb = std::max<int>(0, iv[I_CACHE_CEIL_MB]);
                break;
        }
        switch (iv[I_DENSE_MODE]) {
            case 0: m.dense_weights = bmoe::DenseWeightsMode::Mmap; break;
            case 1: m.dense_weights = bmoe::DenseWeightsMode::Warmed; break;
            case 3: m.dense_weights = bmoe::DenseWeightsMode::Pinned; break;
            default: m.dense_weights = bmoe::DenseWeightsMode::Anonymous; break;
        }
#ifdef BMOE_HAVE_EXPERT_READY_HOOK
        m.overlap = iv[I_OVERLAP] != 0;
#else
        m.overlap = false; // the ggml-cpu expert-ready hook is not compiled in (patch 0002 missing)
#endif
        const int prefetch = std::clamp<int>(iv[I_PREFETCH_LAYERS], 0, bmoe::MoeStreamConfig::prefetch_layers_max);
        if (prefetch > 0 && cache_on) m.prefetch_layers = prefetch;
        if (iv[I_PREDICT_PREFETCH] != 0 && cache_on && m.prefetch_layers == 0) {
            m.predict_prefetch = true;
            m.predict_spec_max = std::max<int>(0, iv[I_PREDICT_SPEC_MAX]);
        }
        const bool spec_on = iv[I_SPEC_SOURCE] != 0;
        const int route_ahead = std::clamp<int>(iv[I_ROUTE_AHEAD], 0, bmoe::MoeStreamConfig::route_ahead_max);
        if (route_ahead > 0 && m.prefetch_layers == 0 && !m.predict_prefetch && !spec_on) m.route_ahead = route_ahead;
        if (iv[I_DROP_COLD_PCT] > 0 && cache_on) {
            m.drop_cold_frac = std::clamp<float>(static_cast<float>(iv[I_DROP_COLD_PCT]) / 100.0f, 0.0f, 1.0f);
        }
        m.row_stream = iv[I_ROW_STREAM] != 0;
        if (iv[I_RELEASE_MMAP] != 0 && (m.dense_weights == bmoe::DenseWeightsMode::Anonymous ||
                                        m.dense_weights == bmoe::DenseWeightsMode::Pinned)) {
            m.release_mmap = true;
        }
        if (iv[I_SUBSTITUTE_PCT] > 0 && cache_on) {
            m.substitute_lambda = std::clamp<float>(static_cast<float>(iv[I_SUBSTITUTE_PCT]) / 100.0f, 0.0f, 1.0f);
        }
    }
    // Speculation is a decode-loop change, not a residency policy: applies to the mmap baseline too.
    if (iv[I_SPEC_SOURCE] == 1) {
        cfg.spec.source = bmoe::DraftSource::mtp;
        cfg.spec.draft_max = std::clamp<int>(iv[I_DRAFT_MAX], 1, bmoe::SpecConfig::draft_max_limit);
        if (iv[I_MTP_P_MIN_PCT] > 0) cfg.spec.draft_p_min = static_cast<float>(iv[I_MTP_P_MIN_PCT]) / 100.0f;
    } else if (iv[I_SPEC_SOURCE] == 2) {
        cfg.spec.source = bmoe::DraftSource::ngram;
        cfg.spec.draft_max = std::clamp<int>(iv[I_DRAFT_MAX], 1, bmoe::SpecConfig::draft_max_limit);
    }

    LOGI("Opening Session: n_ctx=%d n_batch=%d ubatch=%d threads=%d streaming=%d cache_mode=%d cache_mb=%d "
         "dense=%d overlap=%d drop=%d topk_override=%d spec=%d",
         cfg.n_ctx, cfg.n_batch, cfg.n_ubatch, cfg.n_threads, streaming ? 1 : 0, static_cast<int>(iv[I_CACHE_MODE]),
         cfg.moe.cache_mb, static_cast<int>(iv[I_DENSE_MODE]), cfg.moe.overlap ? 1 : 0, static_cast<int>(iv[I_DROP_COLD_PCT]),
         cfg.n_expert_used, static_cast<int>(iv[I_SPEC_SOURCE]));

    std::string error;
    std::unique_ptr<bmoe::Session> opened = bmoe::Session::open(cfg, error);
    if (!opened) {
        LOGE("Session::open failed: %s", error.c_str());
        return to_bytes(env, error.empty() ? std::string("Session::open failed (no message)") : error);
    }

    std::string info;
    info += "arch=" + opened->arch() + "\n";
    info += "n_ctx=" + std::to_string(opened->n_ctx()) + "\n";
    info += "n_expert_used=" + std::to_string(opened->n_expert_used()) + "\n";
    info += "load_seconds=" + std::to_string(opened->load_seconds()) + "\n";
    info += std::string("think_control=") + bmoe::think_control_name(opened->think_control()) + "\n";
    info += std::string("overlap=") + (cfg.moe.overlap ? "1" : "0") + "\n";
    info += std::string("streaming=") + (streaming ? "1" : "0") + "\n";
    info += std::string("vision=") + (opened->supports_vision() ? "1" : "0") + "\n";

    std::unique_ptr<bmoe::IMetricsSink> csv;
    if (!csv_path.empty()) {
        csv.reset(bmoe::make_csv_metrics_sink(csv_path));
        if (!csv) LOGW("could not open metrics CSV at %s", csv_path.c_str());
    }

    {
        std::lock_guard<std::mutex> lk(g_session_mu);
        g_session = std::shared_ptr<bmoe::Session>(std::move(opened));
        g_csv_sink = std::move(csv);
        g_model_info = info;
    }
    LOGI("Session opened");
    return to_bytes(env, std::string());
}

// Bit 0: libonedge-engine was built with the expert-ready hook (I/O-compute overlap possible).
// Bit 1: built with multimodal support (libmtmd).
JNIEXPORT jint JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeCapabilities(JNIEnv *, jobject) {
    jint caps = 0;
#ifdef BMOE_HAVE_EXPERT_READY_HOOK
    caps |= 1;
#endif
#ifdef BMOE_HAVE_MTMD
    caps |= 2;
#endif
#ifdef BMOE_HAVE_HEXAGON
    caps |= 4;
#endif
    return caps;
}

JNIEXPORT jbyteArray JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeModelInfo(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lk(g_session_mu);
    return to_bytes(env, g_model_info);
}

// ---------------------------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeStopGeneration(JNIEnv *, jobject) {
    std::shared_ptr<bmoe::Session> s = current_session();
    if (s) s->cancel();
}

// system_prompt + history_flat ([role0, content0, role1, content1, ...]) only take effect when
// clear_kv is true (see patches/0001-session-chat-context.patch).
// Returns JNI_TRUE when the call completed (including a user cancel); the outcome, error text and
// telemetry are delivered through callback.onFinished().
JNIEXPORT jboolean JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeGenerate(
    JNIEnv * env, jobject /* thiz */,
    jbyteArray j_system, jobjectArray j_history_flat, jbyteArray j_prompt,
    jint max_tokens, jboolean clear_kv, jboolean think,
    jintArray j_image_dims, jobjectArray j_image_data,
    jobject callback)
{
    if (callback == nullptr) return JNI_FALSE;

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token_mid = env->GetMethodID(cb_cls, "onToken", "([B[B)Z");
    jmethodID on_finished_mid = env->GetMethodID(cb_cls, "onFinished", "(ZZ[B[B[BIIIDDD)V");
    if (on_token_mid == nullptr || on_finished_mid == nullptr) {
        LOGE("callback is missing onToken/onFinished");
        return JNI_FALSE;
    }

    auto finish = [&](bool ok, bool cancelled, const std::string & error, const std::string & answer,
                      const std::string & reasoning, int n_gen, int n_prompt, int n_past, double tps,
                      double prefill_s, double cache_hit) {
        jbyteArray j_err = to_bytes(env, error);
        jbyteArray j_ans = to_bytes(env, answer);
        jbyteArray j_rea = to_bytes(env, reasoning);
        env->CallVoidMethod(callback, on_finished_mid, static_cast<jboolean>(ok), static_cast<jboolean>(cancelled),
                            j_err, j_ans, j_rea, static_cast<jint>(n_gen), static_cast<jint>(n_prompt),
                            static_cast<jint>(n_past), static_cast<jdouble>(tps), static_cast<jdouble>(prefill_s),
                            static_cast<jdouble>(cache_hit));
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(j_err);
        env->DeleteLocalRef(j_ans);
        env->DeleteLocalRef(j_rea);
    };

    std::shared_ptr<bmoe::Session> session = current_session();
    if (!session) {
        finish(false, false, "No model is loaded", "", "", 0, 0, 0, 0.0, 0.0, -1.0);
        return JNI_TRUE;
    }

    bmoe::GenerateRequest req;
    req.prompt = from_bytes(env, j_prompt);
    req.n_predict = max_tokens > 0 ? static_cast<int>(max_tokens) : 0; // 0 = fill the remaining context (patch)
    req.think = (think == JNI_TRUE);
    req.clear_kv = (clear_kv == JNI_TRUE);
    req.render_text = true; // engine-side, template-aware split of answer vs reasoning
    if (req.clear_kv) {
        req.system_prompt = from_bytes(env, j_system);
        if (j_history_flat != nullptr) {
            const jsize n = env->GetArrayLength(j_history_flat);
            for (jsize i = 0; i + 1 < n; i += 2) {
                auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(j_history_flat, i));
                auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(j_history_flat, i + 1));
                bmoe::ChatTurn turn;
                turn.role = from_bytes(env, role);
                turn.content = from_bytes(env, content);
                req.history.push_back(std::move(turn));
                env->DeleteLocalRef(role);
                env->DeleteLocalRef(content);
            }
        }
    }

    // Images for this turn: dims = [w0, h0, w1, h1, ...], data[i] = w*h*3 RGB bytes (patch 0003).
    if (j_image_dims != nullptr && j_image_data != nullptr) {
        const jsize n_img = env->GetArrayLength(j_image_data);
        std::vector<jint> dims(static_cast<size_t>(n_img) * 2u, 0);
        if (env->GetArrayLength(j_image_dims) >= n_img * 2) env->GetIntArrayRegion(j_image_dims, 0, n_img * 2, dims.data());
        for (jsize i = 0; i < n_img; ++i) {
            auto data = static_cast<jbyteArray>(env->GetObjectArrayElement(j_image_data, i));
            bmoe::ImageRGB img;
            img.width = dims[static_cast<size_t>(i) * 2u];
            img.height = dims[static_cast<size_t>(i) * 2u + 1u];
            const std::string raw = from_bytes(env, data);
            img.rgb.assign(raw.begin(), raw.end());
            req.images.push_back(std::move(img));
            env->DeleteLocalRef(data);
        }
    }

    std::string sent_answer;
    std::string sent_reasoning;
    bool callback_failed = false;

    // Runs on this thread, inside generate(): env is valid here (see file header).
    auto on_token = [&](const bmoe::TokenMetrics & m) {
        if (callback_failed) return;
        std::string answer_delta, reasoning_delta;
        const bool a = next_delta(sent_answer, m.text, answer_delta);
        const bool r = next_delta(sent_reasoning, m.reasoning, reasoning_delta);
        if (!a && !r) return;

        jbyteArray j_a = to_bytes(env, answer_delta);
        jbyteArray j_r = to_bytes(env, reasoning_delta);
        const jboolean keep_going = env->CallBooleanMethod(callback, on_token_mid, j_a, j_r);
        env->DeleteLocalRef(j_a);
        env->DeleteLocalRef(j_r);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            callback_failed = true;
            session->cancel();
            return;
        }
        if (!keep_going) session->cancel();
    };

    bmoe::RunResult result;
    {
        std::lock_guard<std::mutex> gen_lk(g_generate_mu);
        result = session->generate(req, on_token, g_csv_sink.get());
    }

    if (!result.ok) {
        LOGE("Session::generate failed: %s", result.error.c_str());
        finish(false, false, result.error.empty() ? std::string("generate failed (no message)") : result.error, "",
               "", 0, 0, 0, 0.0, 0.0, -1.0);
        return JNI_TRUE;
    }

    const bmoe::RunSummary & s = result.summary;
    finish(true, result.cancelled, "", result.generated_text, result.reasoning_text, s.n_generated, s.n_prompt,
           s.n_past, s.tokens_per_second, s.prefill_seconds, s.cache_hit_pct);
    return JNI_TRUE;
}


static std::string json_escape(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeDecide(
    JNIEnv * env, jobject /* thiz */,
    jbyteArray j_prefix, jbyteArray j_suffix, jobjectArray j_choices, jboolean reuse_prefix)
{
    std::shared_ptr<bmoe::Session> session = current_session();
    if (!session) return to_bytes(env, "{\"ok\":false,\"error\":\"No model is loaded\"}");

    bmoe::DecideRequest req;
    req.prefix = from_bytes(env, j_prefix);
    req.suffix = from_bytes(env, j_suffix);
    req.reuse_prefix = reuse_prefix == JNI_TRUE;
    if (j_choices != nullptr) {
        const jsize n = env->GetArrayLength(j_choices);
        req.choices.reserve(static_cast<size_t>(n));
        for (jsize i = 0; i < n; ++i) {
            auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(j_choices, i));
            req.choices.push_back(from_bytes(env, item));
            env->DeleteLocalRef(item);
        }
    }

    bmoe::DecideResult result;
    {
        std::lock_guard<std::mutex> gen_lk(g_generate_mu);
        result = session->decide(req);
    }

    std::string out = "{\"ok\":";
    out += result.ok ? "true" : "false";
    out += ",\"cancelled\":";
    out += result.cancelled ? "true" : "false";
    out += ",\"fatal\":";
    out += result.fatal ? "true" : "false";
    out += ",\"error\":\"" + json_escape(result.error) + "\"";
    out += ",\"best\":" + std::to_string(result.best);
    out += ",\"choice_logp\":[";
    for (size_t i = 0; i < result.choice_logp.size(); ++i) {
        if (i) out += ",";
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%.17g", result.choice_logp[i]);
        out += buf;
    }
    out += "]";
    char buf[128];
    std::snprintf(buf, sizeof(buf), ",\"prefill_s\":%.6f,\"n_prefilled\":%d,\"n_reused\":%d,\"prefix_state_mib\":%.3f",
                  result.prefill.seconds, result.n_prefilled, result.n_reused,
                  static_cast<double>(result.prefix_state_bytes) / (1024.0 * 1024.0));
    out += buf;
    out += "}";
    return to_bytes(env, out);
}

} // extern "C"
