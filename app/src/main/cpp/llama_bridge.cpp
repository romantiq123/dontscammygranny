// ADR-002/003/004: JNI-мост между Kotlin (LlamaBridge.kt) и llama.cpp.
// CPU-only, один контекст на всё приложение, доступ сериализован на Kotlin-
// стороне (ShieldPlanner.dispatcher, ADR-005) — этот файл НЕ потокобезопасен
// сам по себе и не обязан быть, вызовы гарантированно не пересекаются.
//
// ВАЖНО: llama.cpp не держит стабильный C API между релизами (сигнатуры вроде
// llama_model_load_from_file/llama_init_from_model периодически переименовываются
// и меняются). Этот файл писался без возможности собрать/прогнать его на
// реальном llama.cpp-дереве — при вендоринге конкретного коммита (см.
// app/README.md) свериться с actual `llama.h` этого коммита и поправить при
// необходимости, прежде чем считать баг в рантайме багом логики, а не API-дрейфа.

#include <jni.h>
#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <ctime>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

// Маркер сборки — печатается в лог при старте. Бампать при каждом изменении
// нативного кода, чтобы на устройстве было видно, тот ли APK запущен.
#define NATIVE_BUILD_TAG "native-2026-07-22-b-kvclear+compact-gbnf"

#define LOG_TAG "LlamaBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Путь к файлу лога (тот же shield.log, что пишет Kotlin FileLog) — задаётся из
// Kotlin через nativeSetLogPath. Нативные «хлебные крошки» идут ТУДА ЖЕ, потому
// что LOGE/__android_log виден только в logcat (нужен компьютер), а у тестера
// его нет. Пишем append + fflush + fclose на каждый вызов: даже если следующий
// нативный вызов уронит процесс сегфолтом, последняя строка уже на диске и
// точно указывает, ГДЕ упало.
std::string g_logPath;

void flog(const char* fmt, ...) {
    if (g_logPath.empty()) return;
    FILE* f = fopen(g_logPath.c_str(), "a");
    if (f == nullptr) return;
    time_t t = time(nullptr);
    struct tm tmv;
    localtime_r(&t, &tmv);
    char ts[32];
    strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tmv);
    fprintf(f, "%s I/NativeBridge: ", ts);
    va_list args;
    va_start(args, fmt);
    vfprintf(f, fmt, args);
    va_end(args);
    fputc('\n', f);
    fflush(f);
    fclose(f);
}

struct LlamaHandle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    const llama_vocab* vocab = nullptr;
};

std::string jstringToUtf8(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

void destroyHandle(LlamaHandle* handle) {
    if (handle == nullptr) return;
    if (handle->sampler != nullptr) llama_sampler_free(handle->sampler);
    if (handle->ctx != nullptr) llama_free(handle->ctx);
    if (handle->model != nullptr) llama_model_free(handle->model);
    delete handle;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_kz_invisibleshield_app_llm_LlamaBridge_nativeSetLogPath(
    JNIEnv* env, jobject /* thiz */, jstring path) {
    g_logPath = jstringToUtf8(env, path);
    flog("nativeSetLogPath ok, build=%s", NATIVE_BUILD_TAG);
}

JNIEXPORT jlong JNICALL
Java_kz_invisibleshield_app_llm_LlamaBridge_nativeLoad(
    JNIEnv* env, jobject /* thiz */,
    jstring modelPath, jstring grammarText, jint nThreads, jint nCtx) {

    flog("nativeLoad: enter (build=%s)", NATIVE_BUILD_TAG);
    llama_backend_init();

    const std::string modelPathStr = jstringToUtf8(env, modelPath);
    std::string grammarStr = jstringToUtf8(env, grammarText);

    // Защита от CRLF: парсер GBNF llama.cpp чувствителен к структуре строк, а
    // git на Windows может подсунуть grammar-asset с \r\n. Убираем '\r' —
    // остаются чистые '\n' (см. также .gitattributes eol=lf для *.gbnf).
    grammarStr.erase(std::remove(grammarStr.begin(), grammarStr.end(), '\r'), grammarStr.end());

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0; // ADR-002: CPU-only

    llama_model* model = llama_model_load_from_file(modelPathStr.c_str(), modelParams);
    if (model == nullptr) {
        LOGE("не удалось загрузить модель: %s", modelPathStr.c_str());
        return 0;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(nCtx);
    ctxParams.n_threads = nThreads;
    ctxParams.n_threads_batch = nThreads;

    llama_context* ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("не удалось создать llama_context");
        llama_model_free(model);
        return 0;
    }

    // GBNF-constrained decoding (ADR-004) — root-правило фиксировано в
    // verdict.gbnf. Greedy-сэмплер поверх грамматики: схема вердикта — это
    // enum/числа, а не творческая генерация, детерминизм здесь предпочтительнее
    // случайности при равном наборе валидных продолжений.
    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    llama_sampler* chain = llama_sampler_chain_init(samplerParams);

    // КРИТИЧНО: llama_sampler_init_grammar возвращает nullptr, если грамматика не
    // распарсилась. Добавлять nullptr в цепочку НЕЛЬЗЯ — первый же
    // llama_sampler_sample пройдётся по цепочке и дёрнет apply() у null-сэмплера
    // => SIGSEGV в nativeInfer (баг, найденный на устройстве 2026-07-21: nativeLoad
    // возвращал handle, а первый инференс молча ронял процесс). Проверяем явно:
    // если грамматики нет — работаем без неё (greedy), чтобы НЕ падать. Модель
    // тогда выдаст свободный текст, parseVerdict его не разберёт -> откат на regex
    // (ADR-007), но процесс жив и в лог попадает явная причина.
    llama_sampler* grammarSampler = llama_sampler_init_grammar(vocab, grammarStr.c_str(), "root");
    if (grammarSampler != nullptr) {
        llama_sampler_chain_add(chain, grammarSampler);
        flog("nativeLoad: грамматика распарсилась ОК, сэмплер с GBNF");
    } else {
        LOGE("GBNF-грамматика не распарсилась (llama_sampler_init_grammar=nullptr) — "
             "инференс пойдёт БЕЗ грамматики, вывод скорее всего не сматчит схему");
        flog("nativeLoad: ГРАММАТИКА НЕ РАСПАРСИЛАСЬ — инференс без GBNF (вывод не по схеме)");
    }
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    auto* handle = new LlamaHandle{model, ctx, chain, vocab};
    flog("nativeLoad: done, handle ok");
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_kz_invisibleshield_app_llm_LlamaBridge_nativeInfer(
    JNIEnv* env, jobject /* thiz */,
    jlong handlePtr, jstring prompt, jint maxTokens) {

    flog("nativeInfer: enter");
    auto* handle = reinterpret_cast<LlamaHandle*>(handlePtr);
    if (handle == nullptr) {
        LOGE("nativeInfer вызван с нулевым handle");
        flog("nativeInfer: handle == null, выходим");
        return env->NewStringUTF("");
    }

    // КАЖДЫЙ nativeInfer — независимый запрос, но ctx/sampler переиспользуются между
    // вызовами. ОБЯЗАТЕЛЬНО сбрасываем их в начале, иначе (найдено на устройстве
    // 2026-07-22):
    //  - KV-кэш копит токены от вызова к вызову -> переполнение n_ctx -> llama_decode
    //    возвращает !=0 уже на 2-3-м SMS (и растёт latency: attention по всё большему
    //    контексту);
    //  - grammar-сэмплер остаётся в конечном состоянии прошлого разбора -> новый JSON
    //    не начинается с нуля.
    llama_memory_clear(llama_get_memory(handle->ctx), true);
    llama_sampler_reset(handle->sampler);
    flog("nativeInfer: KV-кэш очищен, сэмплер сброшен");

    const std::string promptStr = jstringToUtf8(env, prompt);
    flog("nativeInfer: промпт получен, %zu байт", promptStr.size());

    // Токенизация промпта.
    const int nPromptTokensMax = static_cast<int>(promptStr.size()) + 16;
    std::vector<llama_token> promptTokens(nPromptTokensMax);
    const int nPromptTokens = llama_tokenize(
        handle->vocab, promptStr.c_str(), static_cast<int32_t>(promptStr.size()),
        promptTokens.data(), nPromptTokensMax, /*add_special=*/true, /*parse_special=*/true);
    if (nPromptTokens < 0) {
        LOGE("токенизация промпта не влезла в буфер");
        flog("nativeInfer: токенизация не влезла в буфер (nPromptTokens=%d)", nPromptTokens);
        return env->NewStringUTF("");
    }
    promptTokens.resize(nPromptTokens);
    flog("nativeInfer: токенизировано %d токенов", nPromptTokens);

    llama_batch batch = llama_batch_get_one(promptTokens.data(), static_cast<int32_t>(promptTokens.size()));
    flog("nativeInfer: -> llama_decode(prompt)");
    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("llama_decode(prompt) провалился");
        flog("nativeInfer: llama_decode(prompt) вернул != 0");
        return env->NewStringUTF("");
    }
    flog("nativeInfer: <- llama_decode(prompt) ok, входим в цикл генерации");

    std::string output;
    llama_token newToken;
    for (int i = 0; i < maxTokens; i++) {
        flog("nativeInfer: iter %d -> sample", i);
        newToken = llama_sampler_sample(handle->sampler, handle->ctx, -1);
        flog("nativeInfer: iter %d <- sampled id=%d", i, newToken);
        // НЕ вызываем llama_sampler_accept здесь: llama_sampler_sample УЖЕ делает
        // accept внутри (см. llama.cpp/src/llama-sampler.cpp, конец функции
        // llama_sampler_sample). Повторный accept продвигал бы состояние
        // grammar-сэмплера (PDA/стек) ДВАЖДЫ на один токен -> порча состояния и
        // SIGSEGV на первом же токене (диагностировано на устройстве 2026-07-22:
        // краш ровно после "sampled id=..." до вывода piece).

        if (llama_vocab_is_eog(handle->vocab, newToken)) {
            flog("nativeInfer: iter %d — EOG, стоп", i);
            break;
        }

        char piece[64];
        const int n = llama_token_to_piece(handle->vocab, newToken, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);
        flog("nativeInfer: iter %d — piece n=%d, output_len=%zu", i, n, output.size());

        llama_batch nextBatch = llama_batch_get_one(&newToken, 1);
        if (llama_decode(handle->ctx, nextBatch) != 0) {
            LOGE("llama_decode(next token) провалился");
            flog("nativeInfer: iter %d — llama_decode(next) вернул != 0, стоп", i);
            break;
        }
    }

    flog("nativeInfer: done, output_len=%zu", output.size());
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_kz_invisibleshield_app_llm_LlamaBridge_nativeFree(
    JNIEnv* /* env */, jobject /* thiz */, jlong handlePtr) {
    destroyHandle(reinterpret_cast<LlamaHandle*>(handlePtr));
}

} // extern "C"
