// LOOM Studio JNI bridge to llama.cpp (pinned tag in build_studio.bat). Kotlin side:
// com.loom.studio.runtime.LlamaNative. One model, one context per LlamaSession; the Weaver's
// thread knob is applied live with llama_set_n_threads, the context-length knob by building a
// new context in the background and swapping between replies.
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>
#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LOOM_LLM", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LOOM_LLM", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace {

std::once_flag g_backend_once;
thread_local std::string g_last_error;

struct Ctx {
    llama_context * ctx = nullptr;
    llama_batch_ext * batch = nullptr;
    llama_sampler * smpl = nullptr;
    const llama_model * model = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string pending;   // bytes of an incomplete UTF-8 sequence
};

void init_backend() {
    std::call_once(g_backend_once, [] {
        llama_log_set([](enum ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
        }, nullptr);
        llama_backend_init();
    });
}

void batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, n - 1, true);
}

int ctx_used(Ctx * c) {
    return llama_memory_seq_pos_max(llama_get_memory(c->ctx), 0) + 1;
}

// Length of the longest prefix of s that is complete UTF-8.
size_t utf8_complete_prefix(const std::string & s) {
    size_t i = 0, last_ok = 0;
    while (i < s.size()) {
        unsigned char ch = static_cast<unsigned char>(s[i]);
        size_t len = ch < 0x80 ? 1 : (ch >> 5) == 0x6 ? 2 : (ch >> 4) == 0xE ? 3 : (ch >> 3) == 0x1E ? 4 : 1;
        if (i + len > s.size()) break;
        i += len;
        last_ok = i;
    }
    return last_ok;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    if (a && !s.empty()) env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

std::string from_jstring(JNIEnv * env, jstring js) {
    if (!js) return {};
    const jsize n = env->GetStringUTFLength(js);
    const char * p = env->GetStringUTFChars(js, nullptr);
    std::string s(p, n);
    env->ReleaseStringUTFChars(js, p);
    return s;
}

std::string from_jbytes(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

} // namespace

extern "C" {

#define JNI(name) Java_com_loom_studio_runtime_LlamaNative_##name

JNIEXPORT jstring JNICALL JNI(lastError)(JNIEnv * env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jlong JNICALL JNI(loadModel)(JNIEnv * env, jobject, jstring jpath, jboolean use_mmap) {
    init_backend();
    std::string path = from_jstring(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    (void) use_mmap; // load mode: llama.cpp default (memory-mapped)
    llama_model * m = llama_model_load_from_file(path.c_str(), mp);
    if (!m) { g_last_error = "llama.cpp could not load the model (unsupported architecture or corrupted file)"; return 0; }
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL JNI(freeModel)(JNIEnv *, jobject, jlong model) {
    if (model) llama_model_free(reinterpret_cast<llama_model *>(model));
}

JNIEXPORT jstring JNICALL JNI(modelInfo)(JNIEnv * env, jobject, jlong model) {
    auto * m = reinterpret_cast<llama_model *>(model);
    char buf[256] = {0};
    llama_model_desc(m, buf, sizeof(buf));
    std::string s = std::string(buf) + " · " + std::to_string(llama_model_n_params(m) / 1000000) + "M params";
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jlong JNICALL JNI(newContext)(JNIEnv *, jobject, jlong model, jint n_ctx, jint n_threads) {
    auto * m = reinterpret_cast<llama_model *>(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = static_cast<uint32_t>(n_ctx < 512 ? n_ctx : 512);
    cp.n_ubatch = cp.n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(m, cp);
    if (!ctx) { g_last_error = "could not create a llama context (out of memory?)"; return 0; }
    auto * c = new Ctx();
    c->ctx = ctx;
    c->model = m;
    c->vocab = llama_model_get_vocab(m);
    c->batch = llama_batch_ext_init(ctx);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL JNI(freeContext)(JNIEnv *, jobject, jlong h) {
    auto * c = reinterpret_cast<Ctx *>(h);
    if (!c) return;
    if (c->smpl) llama_sampler_free(c->smpl);
    if (c->batch) llama_batch_ext_free(c->batch);
    if (c->ctx) llama_free(c->ctx);
    delete c;
}

JNIEXPORT void JNICALL JNI(setThreads)(JNIEnv *, jobject, jlong h, jint n) {
    auto * c = reinterpret_cast<Ctx *>(h);
    llama_set_n_threads(c->ctx, n, n);
}

JNIEXPORT jint JNICALL JNI(nCtx)(JNIEnv *, jobject, jlong h) {
    return static_cast<jint>(llama_n_ctx(reinterpret_cast<Ctx *>(h)->ctx));
}

JNIEXPORT jint JNICALL JNI(nCtxUsed)(JNIEnv *, jobject, jlong h) {
    return ctx_used(reinterpret_cast<Ctx *>(h));
}

JNIEXPORT void JNICALL JNI(reset)(JNIEnv *, jobject, jlong h) {
    auto * c = reinterpret_cast<Ctx *>(h);
    llama_memory_clear(llama_get_memory(c->ctx), true);
    c->pending.clear();
}

// Format a conversation with the model's own chat template (ChatML fallback). UTF-8 bytes in/out.
JNIEXPORT jbyteArray JNICALL JNI(applyTemplate)(JNIEnv * env, jobject, jlong model, jobjectArray roles, jobjectArray contents, jboolean add_assistant) {
    auto * m = reinterpret_cast<llama_model *>(model);
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), t(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; ++i) {
        r[i] = from_jstring(env, (jstring) env->GetObjectArrayElement(roles, i));
        t[i] = from_jbytes(env, (jbyteArray) env->GetObjectArrayElement(contents, i));
        msgs[i] = { r[i].c_str(), t[i].c_str() };
    }
    const char * tmpl = llama_model_chat_template(m, nullptr);
    std::string out;
    if (tmpl) {
        std::vector<char> buf(4096);
        int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
        if (len > (int) buf.size()) {
            buf.resize(len);
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
        }
        if (len >= 0) out.assign(buf.data(), len);
    }
    if (out.empty()) { // ChatML fallback
        for (jsize i = 0; i < n; ++i) out += "<|im_start|>" + r[i] + "\n" + t[i] + "<|im_end|>\n";
        if (add_assistant) out += "<|im_start|>assistant\n";
    }
    return to_bytes(env, out);
}

// Evaluate text (UTF-8 bytes) at the end of the context. Returns tokens evaluated,
// -1 tokenize error, -2 context full, -3 decode error.
JNIEXPORT jint JNICALL JNI(prefill)(JNIEnv * env, jobject, jlong h, jbyteArray jtext) {
    auto * c = reinterpret_cast<Ctx *>(h);
    std::string text = from_jbytes(env, jtext);
    const bool first = ctx_used(c) == 0;
    int n = -llama_tokenize(c->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, first, true);
    if (n <= 0) return n == 0 ? 0 : -1;
    std::vector<llama_token> toks(n);
    if (llama_tokenize(c->vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, first, true) < 0) return -1;
    const int n_ctx = (int) llama_n_ctx(c->ctx);
    if (ctx_used(c) + n >= n_ctx) return -2;
    const int step = (int) (n_ctx < 512 ? n_ctx : 512);
    for (int i = 0; i < n; i += step) {
        const int k = (n - i) < step ? (n - i) : step;
        batch_set_tokens(c->batch, toks.data() + i, k, ctx_used(c));
        if (llama_process(c->ctx, LLAMA_PROCESS_TYPE_DECODE, c->batch) != 0) return -3;
    }
    return n;
}

JNIEXPORT void JNICALL JNI(setSampler)(JNIEnv *, jobject, jlong h, jfloat temp, jint top_k, jfloat top_p, jint seed) {
    auto * c = reinterpret_cast<Ctx *>(h);
    if (c->smpl) llama_sampler_free(c->smpl);
    c->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(c->smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(c->smpl, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(c->smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(c->smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(c->smpl, llama_sampler_init_dist((uint32_t) seed));
    }
}

// Sample + evaluate one token. Returns UTF-8 bytes of the newly complete text (possibly empty),
// or null at end of generation / when the context is full.
JNIEXPORT jbyteArray JNICALL JNI(nextToken)(JNIEnv * env, jobject, jlong h) {
    auto * c = reinterpret_cast<Ctx *>(h);
    if (!c->smpl) return nullptr;
    llama_token tok = llama_sampler_sample(c->smpl, c->ctx, -1);
    if (llama_vocab_is_eog(c->vocab, tok)) return nullptr;
    char buf[256];
    int n = llama_token_to_piece(c->vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) return nullptr;
    c->pending.append(buf, n);
    if (ctx_used(c) + 1 >= (int) llama_n_ctx(c->ctx)) return nullptr;
    batch_set_tokens(c->batch, &tok, 1, ctx_used(c));
    if (llama_process(c->ctx, LLAMA_PROCESS_TYPE_DECODE, c->batch) != 0) return nullptr;
    const size_t ok = utf8_complete_prefix(c->pending);
    std::string out = c->pending.substr(0, ok);
    c->pending.erase(0, ok);
    return to_bytes(env, out);
}

} // extern "C"
