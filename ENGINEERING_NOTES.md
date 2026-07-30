# ENGINEERING NOTES — Invisible Shield

> **What this is.** A log of engineering findings: the traps we have already stepped
> into, their causes and their fixes. Entries follow the format
> **Symptom → Cause → Fix → How to verify**, so that the next session (AI or human)
> does not rediscover the same things from scratch.
>
> **What this is NOT.** Architectural decisions (those live in
> [decisions.md](decisions.md), the ADR log) and current module status (that lives in
> [HANDOFF.md](HANDOFF.md)).
>
> **Rule for adding entries.** Found a non-trivial root cause of a bug, a platform
> behaviour, or a tool limitation — add an entry here. The "done/not done" status
> lives in HANDOFF; what lives here is the knowledge.

---

## 1. Native layer (llama.cpp + JNI)

The bridge: `app/src/main/cpp/llama_bridge.cpp`. Vendored llama.cpp is a submodule at
`app/src/main/cpp/llama.cpp`, pinned to commit `76f46ad2` (tag `gguf-v0.19.0-1029`).
**llama.cpp does not keep a stable C API between releases** — when updating the
submodule, check against `include/llama.h` of that commit before treating a build
error as a logic bug.

### 1.1 `-O3` never reached ggml → `llama_decode` took 113 seconds

**Symptom.** Inference "hung": decoding a 137-token prompt took ~113 s (expected
<1 s), and the system killed the process before it finished. Rebuilding "did not
help", the APK size did not change.

**Cause.** For the debug variant AGP passes `CMAKE_BUILD_TYPE=Debug`, and the NDK
default `CMAKE_C_FLAGS_DEBUG` is `-g` (effectively `-O0`). Overriding it via
`set(CMAKE_C_FLAGS_DEBUG "-O3" ... FORCE)` **did not work**: the variable landed in
`CMakeCache.txt` but **not in the compile commands of the ggml targets** — verified
against `app/.cxx/.../build.ninja`, where `-O3` did not appear in a single one of the
~198 files while `-g` appeared 198 times. The CI build was fast because CI builds
**Release**, where ggml sets `-O3` on its own (this also explains the APK size
difference: 24 MB debug vs 17 MB CI).

**Fix.** In `app/src/main/cpp/CMakeLists.txt`, **before** `add_subdirectory(llama.cpp)`:
```cmake
add_compile_options(-O3)
add_compile_definitions(NDEBUG)
```
This is a directory-level `COMPILE_OPTIONS` property — it is inherited by every
ggml/llama target and is guaranteed to end up on the compiler command line (unlike
`CMAKE_*_FLAGS_DEBUG`).

**How to verify.** Regenerate CMake with the same command AGP uses (its exact form is
stored in `app/.cxx/<variant>/<hash>/<abi>/metadata_generation_command.txt`), then
search `build.ninja` for `-O3`. After the fix: **193 occurrences**, including
`ggml-cpu.c`.

> ⚠️ **A CLEAN rebuild of the native part is required for this to take effect** — see
> [5.1](#51-a-clean-native-rebuild-is-mandatory).

### 1.2 SIGSEGV on the very first token — double `accept`

**Symptom.** `nativeLoad` returned a valid handle, generation started, but the
process died right after the first token (in the log: `iter 0 <- sampled id=5212` →
the app restarts). `FileLog` had no time to record anything — a native crash.

**Cause.** The generation loop called `llama_sampler_sample(...)` **and then also**
`llama_sampler_accept(...)`. But in this llama.cpp commit `llama_sampler_sample`
**already performs the accept internally** (see `llama.cpp/src/llama-sampler.cpp`,
end of the function). The double accept advanced the grammar sampler's state
(PDA/stack) twice per token → corrupted state → segfault.

**Fix.** Removed the redundant `llama_sampler_accept` from the loop in
`llama_bridge.cpp`.

**How to verify.** The log continues past the first token: `sampled id=…` →
`piece n=…` → … → `EOG, stop` → `<- nativeInfer returned …`, and the app does not
restart.

### 1.3 KV cache not cleared between calls → 2nd and 3rd inference failed

**Symptom.** The first message was processed fine, the second failed at `iter 0`
(`llama_decode(next) returned != 0`), the third failed immediately at prompt decode.

**Cause.** The `llama_context` and the sampler are reused across `nativeInfer` calls,
and tokens **accumulated** in the KV cache from message to message → `n_ctx` overflow
→ `llama_decode` returned an error. Latency also grew (attention over an
ever-growing context). The grammar sampler likewise carried over the final state of
the previous parse.

**Fix.** At the start of every `nativeInfer`:
```cpp
llama_memory_clear(llama_get_memory(handle->ctx), true);
llama_sampler_reset(handle->sampler);
```
> In this commit the KV cache is cleared through the **new memory API**, not
> `llama_kv_cache_clear`.

**How to verify.** Send 3+ messages in a row — all must behave identically; each
inference logs the line `KV cache cleared, sampler reset`.

### 1.4 GBNF: a multi-line rule silently fails to parse → nullptr → segfault

**Symptom.** `nativeLoad` returned a handle, the first `nativeInfer` killed the
process.

**Cause.** llama.cpp's GBNF parser **terminates a rule at the first `\n`** unless the
rule is wrapped in parentheses. Multi-line sequences/alternatives without parentheses
did not parse → `llama_sampler_init_grammar` returned `nullptr` → the `nullptr` was
added to the sampler chain unchecked → the first `llama_sampler_sample` called
`apply()` on null.

**Fix (three parts).**
1. `verdict.gbnf` rewritten following the `json.gbnf` canon: `root` on **a single
   line**, enum lists wrapped in parentheses `( ... )`.
2. `llama_bridge.cpp`: **null-check** on the result of `llama_sampler_init_grammar` —
   if the grammar did not parse, run without it (greedy) and say so explicitly in the
   log instead of crashing.
3. `.gitattributes`: `*.gbnf text eol=lf` — see [1.6](#16-crlf-breaks-the-grammar).

**How to verify.** On load the log shows: `nativeLoad: grammar parsed OK, sampler
with GBNF`. If you see `GRAMMAR DID NOT PARSE` — the output will not match the
schema, but the process survives.

### 1.5 GBNF: `ws*` at the start of `root` → the model gets stuck on whitespace

**Symptom.** Inference ran to completion but produced 64 tokens in a row with the
same id (whitespace) — 67 spaces instead of JSON → `JSONException: End of input`.

**Cause.** `root` began with `ws ::= [ \t\n]*` — unbounded whitespace. A weak
0.5B model gets stuck on it: greedy decoding keeps picking the whitespace token, and
`ws*` **always admits it**, so generation never reaches `{`.

**Fix.** The `ws` rules were removed from the grammar entirely → the grammar forces
**compact** JSON `{"level":...}`: at every structural position exactly one next
character is valid and the model is forced to emit it; the only remaining freedom is
in the enum values (which is precisely the classification).

> Side effect: `validate_gbnf.py` no longer accepts an example with spaces inside the
> JSON — expected, the model cannot generate one either.

### 1.6 CRLF breaks the grammar

**Cause.** Git on Windows with `autocrlf` served `verdict.gbnf` with `\r\n`, which
broke the GBNF parser (see 1.4).

**Fix (two levels).** `.gitattributes` → `*.gbnf text eol=lf`; plus
`llama_bridge.cpp` strips `\r` from the grammar text before handing it to the parser,
just in case.

### 1.7 The grammar copy in assets

`app/src/main/assets/verdict.gbnf` is a **copy** of the root `verdict.gbnf`. The app
loads the one in `assets`. When changing the schema, keep **both** in sync (plus
`verdict_schema.json` — the source of truth, ADR-004).

---

## 2. LLM: prompt and model

### 2.1 ChatML — the main quality unlock

**Symptom.** The model returned valid JSON that was **byte-for-byte identical** on
different inputs:
`{"level":"none","confidence":"0.0","category":"none","reason_code":"no_signal"}`.
I.e. the classification ignored the message content.

**Cause.** The prompt was fed as **raw text**. Qwen instruct models are trained on
the **ChatML** format (`<|im_start|>role … <|im_end|>`) — without it the model is not
in instruction-following mode and greedy decoding collapses into the default answer.

**Fix.** `LlmPromptBuilder` assembles ChatML: `system` (role + rule + feature
glossary) → two **real** `user`/`assistant` few-shot pairs (scam→danger,
benign→none) → the actual query → `<|im_start|>assistant`.

The key details that make this work:
- the bridge's tokenizer is called with `parse_special=true`, so the ChatML markers
  are **real control tokens**, not text;
- the assistant's closing `<|im_end|>` is **EOG** (id `151645` for Qwen), so the
  generation loop stops by itself;
- the system prompt contains an explicit rule: "the regex already found a category —
  **confirm it by default**; `none` only if the text is clearly benign" (a small
  model otherwise gravitates towards `none`).

**Confirmed on device.** Input «Перейдите по ссылке http://kaspi-secure.xyz и
подтвердите данные карты» ("Follow the link and confirm your card details") →
`{"level":"warn","confidence":"0.9","category":"phishing_link",
"reason_code":"suspicious_link_domain"}` — tied to the content. The Aggregator raised
confidence 0.4 → 0.9.

> **Takeaway for future experiments:** before switching to a bigger model for
> quality, make sure the current one receives the prompt **in its native chat
> format**. Here that had a bigger effect than a model generation bump.

### 2.2 Context budget: prompt vs `N_CTX`

ChatML with few-shot costs context: the fixed part of the prompt is ≈ **250–310
tokens**. With `N_CTX=512` only ~120 tokens were left for the message itself — a
long SMS overflowed the context, `llama_decode` returned an error, and the LLM
silently dropped out (falling back to regex is graceful, but the layer did nothing).

**Current values** (`LlamaCppExecutor`): `N_CTX=1024`, `MAX_TOKENS=64`,
`MAX_INPUT_CHARS=1200`, `N_THREADS=4`. For a 0.5B model this is ~25 MB of KV cache.

> When moving to a larger model — recalculate: the KV cache grows, and
> `MAX_INPUT_CHARS` must stay comfortably below `N_CTX` minus the prompt minus the
> generation budget.

### 2.3 Swapping the model — a two-line change

Download integrity is checked against the **server's `Content-Length`**, not a
hard-coded size (the `EXPECTED_MODEL_BYTES` constant was removed). So changing the
ADR-003 candidate means editing only `MODEL_FILENAME` and `MODEL_URL` in
`LlamaCppExecutor` (+ the prompt, if the model uses a different chat template).
`cleanupStaleModels()` then deletes the old `.gguf` by itself, so hundreds of
megabytes do not pile up as dead weight.

Verified candidates (sizes checked via the HF API):

| Model | File | Bytes |
|---|---|---|
| Qwen2.5-0.5B-Instruct Q4_K_M (**current**) | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | 491 400 032 |
| Qwen2.5-1.5B-Instruct Q4_K_M | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | 1 117 320 736 |

### 2.4 The model download must never run inside `infer()`

**Symptom (historical).** The download used to start inside `infer()`, i.e. on the
pipeline's single serialized dispatcher (ADR-005): it blocked it for ~48 s, failed
with a `SocketException` on the large file, and restarted from zero on every SMS —
never completing at all.

**Fix.** `infer()` **never** downloads: no model → it returns `null` (regex-only) and
kicks off a background download. The download itself runs on a separate thread, with
**HTTP Range** resume (the partial file survives in `.part` between attempts),
retries, and a rename to the final name only after the size check — so `infer()` can
never see a half-downloaded file.

---

## 3. Regex / Atomizer

### 3.1 Unicode regexes: desktop JVM ≠ Android

**Symptom.** On device — `NoClassDefFoundError: RegexAtomizerKt`, although the class
is present in the dex.

**Cause.** It is not a missing class but a **static initializer failure** (`<clinit>`)
while compiling the patterns. Specifically:
- on the JVM `\w`/`\s`/`\b` match ASCII only by default, and `(?i)` without `U` does
  not make Cyrillic/Kazakh case-insensitive (unlike Python, where str patterns are
  implicitly Unicode-aware) — without the flags the Cyrillic patterns **silently**
  stopped matching;
- the inline prefix `(?iU)` works on the desktop JVM but **Android rejects it**;
- the `UNICODE_CHARACTER_CLASS` flag is **not supported on Android at all** —
  `IllegalArgumentException: "UNICODE_CHARACTER_CLASS flag not supported"`.

Either error throws in the file's `<clinit>` and on device turns into
`NoClassDefFoundError` (the class is in the dex, but its static initializer crashed —
this is NOT a missing class).

**Fix.** All RU/KZ patterns are compiled through the `ruKz(pattern)` helper, which
replaces `\w` → `[\p{L}\p{N}_]` manually (Android does support `\p{...}` properties)
and sets the flags `CASE_INSENSITIVE | UNICODE_CASE`. `\s`/`\d`/`\b` stay ASCII —
sufficient for our patterns.

> **If a new RU/KZ regex is added bypassing `ruKz` — that is a bug.**

### 3.2 Two-tier OTP: recall vs precision

**A two-iteration story** — useful as an illustration of the ADR-007 trade-off.

1. *Too little recall.* Only «продиктуйте код»/«назовите код» ("dictate the code" /
   "name the code") were caught. A real-world «код скажи…» ("tell me the code")
   yielded `category=none` → **the LLM was not called either** (the
   `category != none` gate) → the scam slipped through.
2. *Too much.* After widening to "verb + код", ordinary developer/everyday chat
   («дай код», «код сообщения» — "give me the code", "the message code") started
   triggering — false alarms. **The LLM cannot suppress those**: the Aggregator's
   `max()` policy (ADR-005) only raises the level, never lowers it — so precision
   has to live in the regex itself.

**The final design — two tiers:**
- **STRONG** (unambiguous, fire on their own): `код подтверждения` (confirmation
  code), `смс-код` (SMS code), `продиктуйте/назовите код`, `никому не сообщайте`
  (do not tell anyone).
- **WEAK** ("verb + код": скажи/дай/сообщи/передай/отправь/напиши) — fire **only
  with scam context nearby** (`OTP_CONTEXT`: смс/банк/каспи/halyk/доставка/
  заблокир…/срочно/счёт/карта/whatsapp…).

Result: «скажи код» on its own — silence; «код скажи, доставка каспи» — an alarm.

**The generalized mechanism.** `Rule` has two corroboration fields, and `atomize()`
checks both:
- `requiresUrl` — the rule only counts if the text contains a URL (this is
  `phishing_link`: an alarming phrase without an actual link is a fragment, not a
  scam);
- `contextAny` — the rule only counts if this context is also present in the text
  (the weak OTP tier).

> When adding a new ambiguous pattern — attach `contextAny` to it instead of
> widening the strong tier.

### 3.3 Bank impersonation: an empty sender ≠ a hidden number

**Symptom.** False `DANGER` on legitimate bank notifications.

**Cause.** The impersonation-by-number check was fed the notification's
`EXTRA_TITLE` — which is the **display name** (for bank apps almost always
alphabetic, e.g. «Kaspi.kz»), not the sender's number. The branch "alphabetic/hidden
number → banks don't call like that" fired on any mention of a bank.

**Fix (two levels).**
- `impersonationFlag()` returns `null` for an empty sender: **no number ≠
  impersonation**.
- The NLS passes the title into the check only if it actually looks like a phone
  number (`looksLikePhoneNumber`: enough digits and no letters).

The textual `bank_impersonation` signal continues to work correctly through
`RegexAtomizer`.

---

## 4. Android platform

### 4.1 The NLS feedback loop

**Symptom.** An endless loop of alerts.

**Cause.** The shield's own heads-up notification («Осторожно…» with the text
«Никому не называйте код…» — "do not tell anyone the code") was read by our own
`NotificationListenerService`, matched the OTP pattern and spawned a new alert.

**Fix.** `if (sbn.packageName == packageName) return` at the top of
`onNotificationPosted`.

> General rule: **any listener that itself produces events of its own kind must
> filter itself out first.**

### 4.2 The NLS reposts the same notifications

**Symptom.** The log showed the same message going through the pipeline **8+ times a
minute**; for a scam notification every repost meant another ~1-second LLM inference.

**Cause.** `onNotificationPosted` fires on any **update/re-ranking** of a
notification, not only on a new one.

**Fix (both BEFORE `scope.launch`, so junk never reaches the dispatcher):**
- `shouldSkip()` — filters persistent/system notifications: flags
  `FLAG_ONGOING_EVENT`, `FLAG_FOREGROUND_SERVICE`, `FLAG_GROUP_SUMMARY` + categories
  `PROGRESS/SERVICE/TRANSPORT/SYSTEM/STATUS/ALARM/NAVIGATION/STOPWATCH` (battery,
  media player, chat summaries are never scams);
- `isDuplicate()` — dedup by `sbn.key` + `hashCode(text)`.

**Check order in `onNotificationPosted`:** own package → call branch → `shouldSkip` →
`extractText` → `isDuplicate` → pipeline.

### 4.3 Messenger calls are invisible to `CallScreeningService`

**Platform limitation.** `CallScreeningService` + `ROLE_CALL_SCREENING` (ADR-011)
sees **only** calls going through the system Telecom stack, i.e. cellular ones.
WhatsApp / Telegram / Viber / Signal / Skype / Google Meet calls are VoIP
(self-managed `ConnectionService`), and `onScreenCall()` **is not invoked** for them.
For the target audience this is currently one of the main attack vectors.

**Workaround (implemented in the NLS).** A messenger's call notification is
recognized by `packageName ∈ VOIP_CALL_PACKAGES` **and** (`category == CATEGORY_CALL`
**or** a full-screen intent is present) — and arms the same "risky-call window" via
`ShieldEngine.onCallStarted`. From there the whole action correlation (OTP SMS /
remote-access install / bank app during the call) works exactly as for cellular
calls.

The caller-identity subtlety: a messenger shows a **name** if the number is in the
contacts, and a **number** if it is not. Hence: title-is-a-name → do **not** arm the
window (otherwise a false DANGER during a legitimate relative's call);
title-is-a-number → unknown caller → arm.

**Honest limitations.** Call audio is unavailable (same as cellular); the number is
known only when the caller is not in the contacts; this is a heuristic over a
notification, not a system contract.

### 4.4 `CallScreeningService` has no call-end callback

**Symptom.** After the very first call from an unknown number the "risky-call
window" stayed open **forever**, and any subsequent OTP SMS was treated as DANGER
"during a call".

**Cause.** `ShieldEngine.onCallEnded()` was never invoked: `CallScreeningService`
only notifies about the **start** of screening.

**Fix — two levels (a safety net + precision):**
1. **Window timeout** `ShieldEngine.RISKY_WINDOW_MS` (10 min) — the window
   self-closes inside `duringRiskyCall()`. Always works, needs no permissions.
2. **Precise call end**: `app/pipeline/CallStateWatcher` listens to
   `TelephonyCallback` (API 31+) / `PhoneStateListener` and calls `onCallEnded()` on
   `CALL_STATE_IDLE`. Requires `READ_PHONE_STATE`; without the permission — graceful
   degradation to the timeout.

For **messenger calls** a precise end signal exists without telephony:
`onNotificationRemoved` for the same `sbn.key` that armed the window.

### 4.5 Data race over `CallState`

**Symptom.** Potentially inconsistent call-window state (manifests rarely — a call
and an SMS must coincide).

**Cause.** `ShieldPlanner.engine` is a singleton but is touched from **three**
threads: `onSms` — from the pipeline dispatcher, `onCallStarted` — from the
`CallScreeningService` Binder thread, `onAppInstalled` — from the
`BroadcastReceiver` main thread. Only the first was serialized.

**Important invariant.** The single-threaded `ShieldPlanner.dispatcher` serializes
**access to the `llama_context`** (an ADR-005 requirement), but **not** access to
`CallState` — call and package events bypass it.

**Fix.** All public methods of `ShieldEngine` are marked `@Synchronized`.

### 4.6 Why the shortService FGS is not wired into the pipeline

**A decision, not a bug.** `ShieldPipelineForegroundService` (ADR-008) remains a
skeleton and **is not used** for running the pipeline. Reasons:
- inference completes in seconds, and the NLS callback keeps the process at
  perceptible importance — it is not at risk of being killed;
- a per-message FGS would produce a **visible notification on every check**;
- starting an FGS from a background NLS callback on Android 12+ risks
  `ForegroundServiceStartNotAllowedException` — i.e. it could **break a working
  path** for near-zero gain.

**Revisit triggers:** moving to a 1.5B+ model (long inference), or logs showing the
process actually being killed mid-inference.

### 4.7 There is no "user opened an app" event

Android has no callback for another app being opened. So while the risky-call window
is open, `BankAppMonitor` **polls** the foreground app via
`UsageStatsManager.queryEvents` (interval ~1.2 s). The `PACKAGE_USAGE_STATS`
permission is already declared; nothing new is needed. Limitation: without a
foreground service the polling lives only as long as the process does.

---

## 5. Build and environment

### 5.1 A clean native rebuild is mandatory

After editing `CMakeLists.txt` or the compile flags, **an incremental build lies to
you**: ninja reuses the old object files, and the APK comes out with the same size
and the same behaviour. That is exactly what the symptom "I build with `-O3` and
nothing changes" looked like.

**How to do it cleanly:** Android Studio → *Build → Refresh Linked C++ Projects* →
*Clean Project* → *Rebuild Project*; or delete `app/.cxx` and `app/build` before
building.

**How to confirm the device runs the new native code:** `llama_bridge.cpp` has
`NATIVE_BUILD_TAG`, printed to the log in `nativeSetLogPath`/`nativeLoad`. The Kotlin
side similarly prints `FileLog.BUILD_TAG` at startup. **Bump both on significant
changes** — cheaper than guessing which APK is installed on the phone.

### 5.2 Gradle fails to start in an AI agent's terminal (loopback) — with a working workaround

**Symptom.** `./gradlew` fails with `java.io.IOException: Unable to establish
loopback connection`.

**Cause.** In the agent terminal's process tree, `Selector.open()` is broken
**JVM-wide** (verified with a bare 5-line test: `Selector.open()` fails while a
plain `ServerSocket.bind(127.0.0.1)` works). Gradle cannot start without a selector.
**It is not the VPN, not the antivirus and not the sandbox** — disabling the VPN and
running unsandboxed do not help. Building through Android Studio itself works fine.

**Workaround for `:core`** (verified): compile directly with the Kotlin compiler
from the Gradle cache — `K2JVMCompiler` needs no selector.

```bash
java -cp "<kotlin-compiler-embeddable-1.9.24.jar>;<kotlin-stdlib-1.9.24.jar>;\
<kotlin-script-runtime.jar>;<kotlin-reflect.jar>;<trove4j.jar>;<annotations-13.0.jar>" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "<stdlib>;<annotations>" -jvm-target 17 -d <out> <all .kt files>
```
The jars live in `~/.gradle/caches/modules-2/files-2.1`. Three hard requirements:
1. **the stdlib version must match the compiler exactly** (1.9.24) — otherwise the
   IR backend crashes on inline functions;
2. **`annotations-13.0.jar` is mandatory** on the compiler classpath, otherwise
   lambda/function-reference codegen fails with
   `ClassNotFoundException: org.jetbrains.annotations.NotNull`;
3. do not generate one giant `main()` — it hits the JVM's 64 KB method size limit.

`:app` **cannot** be built this way (needs AGP, resources/`R`, AndroidX).

### 5.3 Diagnostics without a computer — and its limit

`app/log/FileLog.kt` mirrors Logcat into the file
`getExternalFilesDir/logs/shield.log` — readable right inside the app (the log
screen), because on Android 11+ the system Files app hides `Android/data`. A global
`Thread.setDefaultUncaughtExceptionHandler` additionally logs unhandled Kotlin
exceptions before the crash. The native side writes its breadcrumbs into the **same
file** (`nativeSetLogPath`, `flog()` with `fflush`+`fclose` on every line — so the
last line survives a segfault).

**The limit.** A native crash (SIGSEGV) kills the process instantly — the Kotlin
side has no chance to write anything. Those cases still need `adb logcat` /
a tombstone from a computer. The native breadcrumbs partially close that gap — the
last line shows **where** it died.

---

## 6. Build checkpoints

Tags that show in the log which build is actually installed on the device.

| Tag | What it brought |
|---|---|
| `native-2026-07-21-c` | Valid GBNF (single-line `root`), grammar null-check, `\r` strip |
| `2026-07-22-O3+no-double-accept` / `native-2026-07-22-a-*` | Real `-O3` for ggml; removed the double `accept` (SIGSEGV) |
| `native-2026-07-22-b-kvclear+compact-gbnf` | KV clear + sampler reset on every inference; grammar without `ws` |
| `2026-07-22-dedup+skip-ongoing` | Dedup and system-notification filtering in the NLS |
| `2026-07-22-qwen2.5-0.5b+fewshot` | Qwen2.5-0.5B, ChatML prompt, resume-by-`Content-Length` download |
| `2026-07-22-ctx1024+trim-prompt` | `N_CTX=1024`, trimmed prompt, `MAX_INPUT_CHARS=1200` |

**The LLM unlock chronology** (useful as a map: each next bug was invisible until
the previous one was fixed):

```
GBNF didn't parse   →  segfault on the 1st token
      ↓ (valid grammar + null-check)
native at -O0       →  llama_decode 113 s, process killed
      ↓ (add_compile_options(-O3) + clean rebuild)
double accept       →  segfault right after the 1st token
      ↓ (removed the extra llama_sampler_accept)
KV never cleared    →  2nd and 3rd inference failed at decode
      ↓ (llama_memory_clear + llama_sampler_reset)
ws* in the grammar  →  64 spaces instead of JSON
      ↓ (compact GBNF without ws)
raw prompt          →  constant {"level":"none"} on any input
      ↓ (ChatML + few-shot)
✅ meaningful, content-aware verdicts
```
