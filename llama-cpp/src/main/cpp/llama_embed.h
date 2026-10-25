#pragma once

// Text embeddings on top of the llama.cpp runtime already vendored for the chat provider.
//
// Two callers share this file verbatim:
//
//   * `llama_jni.cpp`, which wraps [EmbedEngine] in the JNI entry points the app calls;
//   * the host-side probe under `xtest/llamacpp-host/embed_probe.cpp`, which runs the very
//     same class against a real GGUF on a workstation.
//
// The second caller is the reason nothing here may include <jni.h> or anything Android: the
// pooling / EOS / context-shape contract is the part that is easy to get subtly wrong and
// expensive to discover, since the only other way to test it is a ~20 minute CI round that
// builds llama.cpp for two ABIs first.

#include "llama.h"

#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace llamajni {

/// What the loaded GGUF turned out to be, read once, when the engine is built.
struct EmbedModelFacts {
    /// Width of one embedding vector. Sized from the model's *output* embedding width, which
    /// is what llama.cpp resizes its pooled buffer to -- not necessarily the input width.
    int32_t dim = 0;

    /// How llama.cpp turns a whole sequence into one vector. Comes from the GGUF header
    /// (`general.pooling_type`) unless a caller overrides it, which is exactly why the engine
    /// must not assume a fixed one: EmbeddingGemma declares MEAN, Qwen3-Embedding declares
    /// LAST, and a model that declares nothing at all gets NONE.
    enum llama_pooling_type pooling = LLAMA_POOLING_TYPE_UNSPECIFIED;

    /// Which of the two run paths this model takes. A decoder call on an encoder-only model is
    /// an error (and vice versa), and the two are not distinguishable from the architecture
    /// string, so this is asked of llama.cpp rather than guessed.
    bool has_encoder = false;
    bool has_decoder = false;

    /// Context length the model was trained on. It is both the friendlier "this text is too long"
    /// number the caller reports and the ceiling the engine clamps its own context to, because a
    /// position past it is not an error llama.cpp can be caught returning (see the constructor).
    int32_t n_ctx_train = 0;
};

/// Stable, lowercase name for a pooling type, for logs and error messages. Positional
/// ordinals are deliberately not used: a llama.cpp bump that inserts a variant would silently
/// relabel every existing value.
inline const char * poolingName(enum llama_pooling_type pooling) {
    switch (pooling) {
        case LLAMA_POOLING_TYPE_UNSPECIFIED: return "unspecified";
        case LLAMA_POOLING_TYPE_NONE:        return "none";
        case LLAMA_POOLING_TYPE_MEAN:        return "mean";
        case LLAMA_POOLING_TYPE_CLS:         return "cls";
        case LLAMA_POOLING_TYPE_LAST:        return "last";
        case LLAMA_POOLING_TYPE_RANK:        return "rank";
    }
    return "unknown";
}

/// Owns one embedding context for one loaded model (the model itself stays owned by the
/// caller; this class never frees it).
///
/// `llama_context` is not thread-safe, so the caller must serialize [embed]. The Kotlin layer
/// does that with a mutex; the host probe simply never overlaps calls.
///
/// The context is created with `pooling_type` left UNSPECIFIED so the GGUF's own choice
/// survives -- forcing MEAN here would look harmless and would silently wreck
/// Qwen3-Embedding. Sizes are unbatched (one text at a time) with `n_ubatch` deliberately
/// equal to `n_batch`: llama.cpp requires that for non-causal models, and there is nothing to
/// gain from splitting a single sequence.
class EmbedEngine {
public:
    EmbedEngine(llama_model * model, uint32_t n_ctx, int n_threads)
        : model_(model), n_ctx_(n_ctx) {
        if (model_ == nullptr) {
            throw std::runtime_error("embedding model handle is null");
        }
        if (n_ctx_ == 0) {
            throw std::runtime_error("embedding context size must be positive");
        }

        // Never build a context larger than the model was trained for.
        //
        // The window is not a preference. A BERT with a 512-token window has 512 position
        // embeddings, and running it at position 600 does not trip a check that can be caught:
        // ggml asserts inside ggml_get_rows and the process dies, taking the whole app with it.
        // Clamping here makes an over-long text fall through to the token-count refusal below,
        // which is an error the caller can report instead of a crash it cannot.
        //
        // It only ever lowers the number, so a model whose window is larger than the requested
        // context (which is all of them except the small ones) behaves exactly as before.
        const int32_t trained = llama_model_n_ctx_train(model_);
        if (trained > 0 && static_cast<uint32_t>(trained) < n_ctx_) {
            n_ctx_ = static_cast<uint32_t>(trained);
        }

        llama_context_params params = llama_context_default_params();
        params.n_ctx           = n_ctx_;
        params.n_batch         = n_ctx_;
        params.n_ubatch        = n_ctx_;
        params.n_seq_max       = 1;
        params.n_threads       = n_threads;
        params.n_threads_batch = n_threads;
        // Without this llama.cpp computes logits, and every embedding call comes back empty.
        params.embeddings      = true;

        ctx_ = llama_init_from_model(model_, params);
        if (ctx_ == nullptr) {
            throw std::runtime_error(
                "failed to create the embedding context; is this really an embedding model?");
        }

        try {
            facts_.dim         = llama_model_n_embd_out(model_);
            facts_.pooling     = llama_pooling_type(ctx_);
            facts_.has_encoder = llama_model_has_encoder(model_);
            facts_.has_decoder = llama_model_has_decoder(model_);
            facts_.n_ctx_train = llama_model_n_ctx_train(model_);

            if (facts_.dim <= 0) {
                throw std::runtime_error("the model reports no embedding width");
            }
            if (facts_.has_encoder && facts_.has_decoder) {
                // llama.cpp's own embedding example refuses this too: there is no defined
                // layer to read a sequence vector from.
                throw std::runtime_error(
                    "encoder-decoder models cannot be used for embeddings");
            }
            if (facts_.pooling == LLAMA_POOLING_TYPE_RANK) {
                // A reranker's output is a relevance score per class, not an embedding.
                throw std::runtime_error(
                    "this model is a reranker (pooling type \"rank\"), not an embedding model");
            }
        } catch (...) {
            llama_free(ctx_);
            ctx_ = nullptr;
            throw;
        }

        batch_ = llama_batch_init(static_cast<int32_t>(n_ctx_), 0, 1);
    }

    ~EmbedEngine() {
        // The batch holds only pointers into its own owned arrays, so it is safe to free
        // first; the context must outlive neither.
        llama_batch_free(batch_);
        if (ctx_ != nullptr) {
            llama_free(ctx_);
        }
    }

    EmbedEngine(const EmbedEngine &) = delete;
    EmbedEngine & operator=(const EmbedEngine &) = delete;

    const EmbedModelFacts & facts() const { return facts_; }

    /// Embeds one text into [out], resized to `facts().dim`.
    ///
    /// The vector is left *unnormalised*. Callers that need a unit vector (cosine similarity)
    /// scale it themselves, which keeps the raw magnitudes visible for debugging and keeps the
    /// normalisation in Kotlin, where it is covered by an ordinary unit test.
    void embed(const std::string & text, std::vector<float> & out) {
        const llama_vocab * vocab = llama_model_get_vocab(model_);

        // `add_special = true` lets the tokenizer honour `add_bos_token` / `add_eos_token`
        // from the GGUF header. That is not cosmetic: last-token pooling reads the *last*
        // position, so a model whose conversion sets `add_eos_token` (Qwen3-Embedding does)
        // depends on the tokenizer appending it. Appending EOS here by hand instead would be
        // right for that one model and wrong for the next.
        int32_t needed = llama_tokenize(
            vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
        if (needed < 0) {
            needed = -needed;  // the negative form is "this many tokens would be needed"
        }
        if (needed == 0) {
            throw std::runtime_error("the tokenizer produced no tokens for this text");
        }
        if (static_cast<uint32_t>(needed) > n_ctx_) {
            throw std::runtime_error(
                "this text needs " + std::to_string(needed) + " tokens but the embedding "
                "context holds " + std::to_string(n_ctx_) + "; split it into smaller chunks");
        }

        std::vector<llama_token> tokens(static_cast<size_t>(needed));
        const int32_t written = llama_tokenize(
            vocab, text.data(), static_cast<int32_t>(text.size()),
            tokens.data(), needed, true, true);
        if (written <= 0) {
            throw std::runtime_error("failed to tokenize the text");
        }
        tokens.resize(static_cast<size_t>(written));

        // Every token asks for its output vector rather than only the last one. The pooled
        // paths ignore the extra outputs, and the no-pooling path below needs them: it has to
        // average the per-token vectors itself.
        batch_.n_tokens = static_cast<int32_t>(tokens.size());
        for (int32_t i = 0; i < batch_.n_tokens; i++) {
            batch_.token[i]     = tokens[static_cast<size_t>(i)];
            batch_.pos[i]       = i;
            batch_.n_seq_id[i]  = 1;
            batch_.seq_id[i][0] = 0;
            batch_.logits[i]    = 1;
        }

        // KV state is meaningless for a finished embedding and actively harmful if reused:
        // llama.cpp would attend over the previous text's tokens as well. Null-safe, which
        // matters below.
        llama_memory_clear(llama_get_memory(ctx_), true);

        // Which of the two run calls this context needs.
        //
        // Not `facts_.has_encoder`: a non-causal model such as EmbeddingGemma 2 reports
        // `has_decoder = true` (it is a decoder-shaped graph run without a causal mask), so
        // that flag picks `llama_decode` - which llama.cpp then quietly redirects to
        // `llama_encode` because the context holds no KV memory, logging a line every single
        // call. A context has memory exactly when it is causal, so asking that question
        // directly is both the condition llama.cpp itself tests and the one that keeps the
        // logs clean.
        const bool causal = llama_get_memory(ctx_) != nullptr;
        const int32_t rc = causal ? llama_decode(ctx_, batch_) : llama_encode(ctx_, batch_);
        if (rc != 0) {
            throw std::runtime_error(
                std::string("the model failed to embed the text (llama_") +
                (causal ? "decode" : "encode") +
                " returned " + std::to_string(rc) + ")");
        }
        llama_synchronize(ctx_);

        out.assign(static_cast<size_t>(facts_.dim), 0.0f);
        if (facts_.pooling == LLAMA_POOLING_TYPE_NONE) {
            meanPoolTokens(out);
            return;
        }

        const float * pooled = llama_get_embeddings_seq(ctx_, 0);
        if (pooled == nullptr) {
            throw std::runtime_error(
                std::string("the model returned no pooled embedding (pooling type \"") +
                poolingName(facts_.pooling) + "\")");
        }
        std::copy(pooled, pooled + facts_.dim, out.begin());
    }

private:
    /// Fallback for a GGUF that declares no pooling type: llama.cpp then hands back one vector
    /// per token and it is on us to reduce them. Mean is the only reduction that does not
    /// privilege an arbitrary position, and it is what EmbeddingGemma asks for explicitly.
    void meanPoolTokens(std::vector<float> & out) {
        const int32_t n = batch_.n_tokens;
        std::fill(out.begin(), out.end(), 0.0f);
        for (int32_t i = 0; i < n; i++) {
            const float * token = llama_get_embeddings_ith(ctx_, i);
            if (token == nullptr) {
                throw std::runtime_error(
                    "the model returned no token embedding at position " + std::to_string(i));
            }
            for (int32_t d = 0; d < facts_.dim; d++) {
                out[static_cast<size_t>(d)] += token[d];
            }
        }
        for (int32_t d = 0; d < facts_.dim; d++) {
            out[static_cast<size_t>(d)] /= static_cast<float>(n);
        }
    }

    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    EmbedModelFacts facts_;
    llama_batch batch_{};
    uint32_t n_ctx_ = 0;
};

}  // namespace llamajni
