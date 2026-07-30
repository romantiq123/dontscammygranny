# Invisible Shield (Невидимый Щит)

**An offline, on-device anti-scam shield for elderly people — Android, Russian and
Kazakh.** Nothing they receive is ever sent to a server.

---

## The problem

Elderly people are not hacked. They are *talked into it*: "this is the bank's security
service", "read me the code from the SMS", "install this app so I can help you",
"move your money to a safe account".

Caller-ID products answer this with blocklists. That does not work here: there is no open
database of scam numbers for Kazakhstan, and the number is spoofed anyway. So the shield
is built on a different premise:

> **The number is only context. The detection lives in the actions taken during the call.**

While a *risky-call window* is open (an incoming call from a number that is not in the
contacts), the shield correlates it with what legitimate Android APIs can see: an OTP SMS
arriving, a remote-access app being installed, a banking app being opened. "Unknown call
+ that action" is the moment money actually leaves — and that is what it catches.

## How it works

The pipeline follows the **ROMA** pattern (Atomizer → Planner → Executor → Aggregator),
reimplemented natively in Kotlin:

```
    incoming notification / SMS / call / package install
                              │
   ┌──────────────────────────▼──────────────────────────┐
   │ ATOMIZER    RU/KZ regex (8 categories) + number class │  cheap, synchronous
   │             most traffic is resolved right here       │
   └──────────────────────────┬──────────────────────────┘
                              │ ambiguous?
   ┌──────────────────────────▼──────────────────────────┐
   │ PLANNER     risky-call window; correlates actions     │
   │             against the currently active call         │
   └──────────────────────────┬──────────────────────────┘
                              │
   ┌──────────────────────────▼──────────────────────────┐
   │ EXECUTOR    on-device LLM (llama.cpp, CPU-only),      │  only when needed
   │             output constrained by a GBNF grammar      │
   └──────────────────────────┬──────────────────────────┘
                              │
   ┌──────────────────────────▼──────────────────────────┐
   │ AGGREGATOR  final = max(regex, llm) — neither layer   │
   │             can LOWER an alert, only raise it         │
   └──────────────────────────┬──────────────────────────┘
                              │
                     warning shown to the user
```

Design properties worth calling out:

- **Regex first, LLM second.** Deterministic RU/KZ patterns catch known schemes cheaply
  and predictably. The small model is consulted only on ambiguous cases and — by policy —
  **cannot cancel** an alert the regex layer raised.
- **Structured model output.** The LLM's answer is constrained by a GBNF grammar
  (`verdict.gbnf`) to a handful of enum fields, so it is physically incapable of emitting
  prose or malformed JSON. Human-readable wording lives in code, not in the model.
- **Sanctioned capture channels only:** `NotificationListenerService` (text),
  `CallScreeningService` + `ROLE_CALL_SCREENING` (calls), `PACKAGE_ADDED`, `UsageStats`.
  The **Accessibility API is deliberately not used** — reasoning in ADR-001.
- **Messenger calls too.** WhatsApp/Telegram/Viber calls are VoIP and invisible to the
  system call screener; they are detected separately, from their notification.

## Privacy

The detection core runs **entirely offline**. Messages, numbers and verdicts never leave
the device — the product has no server side.

The only network access in the whole application is a **one-time model download**
(~491 MB) from HuggingFace, and it happens only after the user explicitly consents
(Wi-Fi-only by default). Without the model the shield still works on the regex layer.

The offline/online boundary is drawn in [`trust_boundary.svg`](trust_boundary.svg); the
legal layer (Kazakhstan's personal data law) is analysed in ADR-006.

## Status

Working prototype: builds, installs on real devices, and both the detection pipeline and
on-device LLM classification are confirmed working on-device.

| Component | Status |
|---|---|
| `:core` — ROMA logic (pure Kotlin/JVM) | ✅ 62 tests |
| Text capture (NLS), calls (CallScreening), package installs | ✅ verified on device |
| Messenger (VoIP) call detection | ✅ verified on device |
| On-device LLM (llama.cpp + GBNF) | ✅ content-aware verdicts confirmed on device |
| Verdict cache, contacts, precise call-end, download consent UX | 🟡 implemented and compiling, on-device verification pending |
| Model benchmark on reference device (ADR-002/003) | ⏳ open blocker for final model choice |
| Accessible output: TTS, full-screen alert (ADR-013) | ⏳ not implemented |
| Online layer (notifying a relative) | 📋 roadmap, out of scope for v1 |

Default model: **Qwen2.5-0.5B-Instruct Q4_K_M**. Swapping candidates is a two-line change
— see [ENGINEERING_NOTES §2.3](ENGINEERING_NOTES.md).

## Building

Requires **JDK 17–21** (Gradle 8.9 does not run on JDK 24), the Android SDK, NDK
`26.1.10909125` and CMake `3.22.1`. The SDK path goes into `local.properties` (untracked).

```bash
git clone --recurse-submodules https://github.com/romantiq123/dontscammygranny
cd dontscammygranny

./gradlew :core:test          # pure JVM, no Android SDK required
./gradlew :app:assembleDebug  # → app/build/outputs/apk/debug/app-debug.apk
```

If you already cloned without submodules:

```bash
git submodule update --init --recursive
```

> The native part (llama.cpp) is built from the submodule. After changing
> `cpp/CMakeLists.txt` you need a **clean** rebuild — otherwise ninja reuses stale object
> files and nothing appears to change
> ([why](ENGINEERING_NOTES.md#51-чистый-пересбор-нативки-обязателен)).

Minimum Android version: **10 (API 29)**. ABI: `arm64-v8a`.

## Repository layout

```
core/                    pure Kotlin/JVM — all ROMA logic, testable without the Android SDK
  atomizer/              RU/KZ regex layer, phone-number classifier
  planner/               ShieldEngine — risky-call window, action correlation
  aggregator/            verdict reconciliation (max policy)
  executor/              LLM contract + prompt builder

app/                     Android
  notification/          NLS — text capture, messenger call detection
  callscreening/         CallScreeningService (ROLE_CALL_SCREENING)
  pipeline/              contacts, call state, watched apps
  llm/                   llama.cpp executor, verdict cache, model download
  cpp/                   JNI bridge + llama.cpp (submodule)
  alert/, ui/, log/      warnings, onboarding, on-device file log

verdict_schema.json      LLM verdict schema (source of truth)
verdict.gbnf             grammar for structured output
*.py                     Python prototypes — behavioural reference for the Kotlin port
```

## Documentation

Project documentation is written in Russian — it is the team's working language.

| File | Purpose |
|---|---|
| [decisions.md](decisions.md) | **ADR log** — every architectural decision and its reasoning. Source of truth for "why this way" |
| [HANDOFF.md](HANDOFF.md) | **Entry point for continuing work** — what is in the code right now, how to build, what's next |
| [ENGINEERING_NOTES.md](ENGINEERING_NOTES.md) | **Engineering findings** — symptom → cause → fix, so the same trap isn't hit twice |
| [app/README.md](app/README.md) | Android module details |

## License

[Apache-2.0](LICENSE). Third-party components and their licenses are listed in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

The RU/KZ scam-pattern layer and the indicator schema are published as a reusable
resource: no open dataset of Russian- and Kazakh-language scam phrasing exists, and that
missing piece is worth building in the open (ADR-014).
