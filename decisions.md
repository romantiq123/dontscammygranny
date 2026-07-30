# Invisible Shield — architectural decision log (ADR)

A compact decision log. The spec references these ADRs instead of retelling them.
Statuses: **Decided** — fixed, not revisited without an explicit request. **Open** — a bottleneck that must be resolved before the spec is finalized.

What does NOT live here: module status and plans — in [HANDOFF.md](HANDOFF.md), bug
post-mortems and findings — in [ENGINEERING_NOTES.md](ENGINEERING_NOTES.md), the public
description — in [README.md](README.md).

## Index

| ADR | Topic | Status |
|---|---|---|
| [001](#adr-001-text-capture--notificationlistenerservice-primary-accessibility-rejected) | Text capture: NLS, Accessibility rejected | Decided |
| [002](#adr-002-inference--llamacpp-android-ndk-cpu-only) | Inference: llama.cpp via NDK, CPU-only | Decided |
| [003](#adr-003-model--a-small-4-bit-gguf-final-choice-after-the-benchmark) | Model: a small 4-bit GGUF | Decided (choice — after the benchmark) |
| [004](#adr-004-structured-output--gbnf-grammar-built-into-llamacpp-not-llguidancexgrammar) | Structured output: GBNF | Decided |
| [005](#adr-005-architecture--the-roma-pattern-native-implementation) | ROMA architecture + Aggregator policy | Decided |
| [006](#adr-006-privacy--offline-core-separated-from-online-features) | Privacy: offline core vs online layer, KZ law | Decided (online deferred) |
| [007](#adr-007-language--rukz-regex-as-the-first-line-llm-refines) | Language: RU/KZ regex as the first line | Decided |
| [008](#adr-008-background-survival-strategy--event-driven-shortservice-not-a-permanent-fgs) | Background: event-driven shortService | Decided (has unverified items) |
| [009](#adr-009-source-of-the-scam-domainnumber-database--open-narrowed) | Scam domain/number database | **Open** |
| [010](#adr-010-scope-of-online-features-in-v1-mvp-boundary--decided-by-deferral) | MVP boundary: v1 = fully offline | Decided |
| [011](#adr-011-call-channel--callscreeningservice--role_call_screening) | Calls: CallScreeningService | Decided |
| [012](#adr-012-sms-channel--nls-in-v1-default-sms-handler-deferred) | SMS: via NLS in v1 | Decided |
| [013](#adr-013-accessible-warning-output-audiences-visionhearing) | Accessible warning output | Decided |
| [014](#adr-014-the-projects-open-component-a-sentient-grant-requirement) | Open component (grant requirement) | Decided |
| [015](#adr-015-adapting-to-new-scam-legends--regex-vocabulary-extension-evoskill-lite-not-self-modifying-grammar) | Adapting to new legends (EvoSkill-Lite) | Decided |

> The file order is historical: 009 and 010 were written after 015 — the index above
> makes that a non-issue.

---

## ADR-001: Text capture — NotificationListenerService (primary), Accessibility rejected
**Status:** Decided (Accessibility reconsideration performed, see below — decision confirmed)
**Context:** Incoming text (SMS, messengers, notifications) must be intercepted for analysis. Calls are a separate channel (ADR-011).
**Options:** Accessibility API; NotificationListenerService; sanctioned narrow roles (SMS handler, CallScreening).
**Decision:** NLS is the primary capture channel. The Accessibility API is NOT used. Depth is added through narrow roles (ADR-011/012), not Accessibility.
**Why (Accessibility rejected, confirmed against 01–02.2026 policy):** (1) `isAccessibilityTool` is granted only to apps whose essence is disability assistance; a scam reader fits that at a stretch, and video review is subjective. (2) Without the flag → prominent disclosure + Permission Declaration Form + approval. (3) January 2026: "AI that reads the screen and presses buttons for the user" is policy-illegal (we press no buttons, but the direction is clear). (4) Android 16 Advanced Protection Mode revokes Accessibility rights from non-tool apps — for a slice of vulnerable users the service would just die. (5) Google itself tells you to prefer narrower APIs. The audience's vision/hearing impairments are NOT an argument for Accessibility on the input side; they are a requirement on the accessible OUTPUT side (ADR-013).
**Consequences:** The NLS sees notifications only (title + text). Blind to: in-app content without a push, truncated long messages, a scam site in the browser, the in-call screen. The gaps are closed with roles (ADR-011/012) and, if desired, a local VPN filter (roadmap) — not Accessibility. A one-way door store-policy-wise.

## ADR-002: Inference — llama.cpp (Android NDK), CPU-only
**Status:** Decided (reconsideration against MLC LLM / ExecuTorch / Cactus performed — confirmed)
**Context:** The model runs on device, offline, hard-tied to GBNF (ADR-004) and to the fragmented zoo of budget Android chips.
**Options:** llama.cpp via NDK; MLC LLM (TVM-compiled); ExecuTorch (Meta, PyTorch-native); Cactus (a 2026 wrapper).
**Decision:** llama.cpp via the NDK, CPU only.
**Why:** (1) GBNF is native to llama.cpp — switching runtimes breaks ADR-004 at the same time, an extra one-way door. (2) llama.cpp is runtime-interpreted, one build runs on any chip; MLC LLM compiles for a specific target (TVM) — on the diverse budget market that means N builds for N SoCs, unacceptable friction. (3) ExecuTorch targets NPU acceleration on flagships — a feature we deliberately do not promise; plus it requires GGUF→.pte conversion, losing the tooling. (4) Cactus by default leans towards a cloud-fallback architecture — clashes with ADR-006 (offline core separate from the opt-in online layer); a small community, risky as an immature dependency. (5) The maturity of the GGUF ecosystem and the speed of community fixes — a track record two developers can rely on.
**Consequences:** We do not exploit NPU/GPU → a lower speed ceiling but higher portability. Any latency numbers — only as measured targets on specific hardware, not promises.
**Needs verification (not measured; does not block the decision, but blocks final numbers in the spec/pitch):**
- The RAM/latency overhead of GBNF sampling on a 3–4 GB device.
- Thread configuration for big.LITTLE budget SoCs (how many threads llama.cpp gets without draining battery/background).
- ABI coverage: `arm64-v8a` only, or add `armeabi-v7a` for really old devices (affects APK size and the claimed audience reach).

## ADR-003: Model — a small 4-bit GGUF, final choice after the benchmark
**Status:** Decided (a set of candidates; the final choice — after the benchmark). Reconsideration 07.2026: a newer same-family candidate found. **Updated 2026-07-30: checked against the code, see "What is actually in the code" below.**

**What is actually in the code (2026-07-30).** The default is **Qwen2.5-0.5B-Instruct
Q4_K_M** (491,400,032 bytes), `MODEL_FILENAME`/`MODEL_URL` in `LlamaCppExecutor`. This
is **not** the final choice in this ADR's sense (the benchmark has not been run) — it
is the working default on which the pipeline was brought to meaningful on-device
verdicts: the Qwen2.5 line is newer than Qwen2-0.5B from the candidate list below at
the same size and speed. Swapping a candidate costs a two-line edit —
[ENGINEERING_NOTES §2.3](ENGINEERING_NOTES.md).

The practical benchmark set has narrowed relative to the original list (below) to
**Qwen2.5-0.5B vs Qwen2.5-1.5B** — see "Next steps" in [HANDOFF.md](HANDOFF.md).
Llama-3.2-1B and Qwen3.5-0.8B remain valid candidates and are not struck off, but
nothing has been measured on them; the final set is the benchmark's call, not this
paragraph's.

**Context:** We need a small model that fits a 3–4 GB budget phone together with the OS and the host app.
**Options:** Qwen2-0.5B; Llama-3.2-1B; TinyLlama; Phi-3-mini (dropped — too heavy); **Qwen3.5-0.8B (new candidate, see below)**.
**Decision:** Benchmark Qwen2-0.5B / Llama-3.2-1B / Qwen3.5-0.8B, 4-bit quant. Final choice — by the benchmark on the reference device.
**Why:** Qwen2-0.5B/Llama-3.2-1B both fit the RAM budget; 0.5B is faster and lighter, 1B is more accurate on Russian. Qwen3.5-0.8B is added to the benchmark because it is the same family a generation newer: it claims a multilingual focus (200+ languages), and reasoning is off by default in the Small series (does not burn tokens on "thinking" — important for CPU latency). The quality/memory/language trade-off is measured, not guessed.
**Consequences:** The LLM is only a refinement layer (ADR-007), not the first line, so even a weak model is acceptable. A benchmark protocol is needed (latency, peak RAM, quality on a labeled RU/KZ set) before the choice is fixed.
**Needs verification before the benchmark:**
- Text-only footprint of Qwen3.5-0.8B: the HF card confirms a "Causal LM with Vision Encoder" architecture — vision is baked into the base model. NOT confirmed but likely (the standard llama.cpp pattern for VL): the vision encoder ships as a separate `mmproj-*.gguf` file and is not loaded unless needed. The Q4_K_M quant weighs just 533 MB — looks like a pure text backbone. Check the repo file list before the benchmark.
- ~~License~~ — VERIFIED: Apache 2.0, confirmed on the unsloth/Qwen3.5-0.8B-GGUF card.
- Actual RU/KZ quality — not proxy numbers, but a measurement on our labeled set. Context from the card: in non-thinking mode (our default) this size shows a marked quality drop against its bigger siblings (MMLU-Pro 29.7 vs 55.3 for Qwen3.5-2B; the multilingual benchmarks in the table are thinking-mode only, ours is below that). Not proof "the model is bad" (the LLM is a secondary refinement layer, ADR-007), but calibrate expectations by measurement, not by the "200+ languages" marketing.

## ADR-004: Structured output — GBNF grammar (built into llama.cpp, not LLGuidance/XGrammar)
**Status:** Decided (reconsideration against LLGuidance/XGrammar performed — confirmed). Prototype: verdict_schema.json, verdict.gbnf, validate_gbnf.py.
**Context:** The model's output must be machine-readable (level, confidence, category, reason_code), not free text.
**Options:** Prompt-only JSON instruction; llama.cpp's built-in GBNF parser (PDA); LLGuidance (Earley parser, integrated into llama.cpp via `LLAMA_LLGUIDANCE`); XGrammar (no native llama.cpp integration, a vLLM/SGLang ecosystem).
**Decision:** llama.cpp's built-in GBNF parser. LLGuidance/XGrammar are NOT pulled in.
**Why:** (1) Guarantees a valid output structure even from a small model — prompt-only JSON breaks on preambles ("Sure! Here is the verdict:"), grammar-constrained decoding physically cannot generate that (verified by validate_gbnf.py). (2) LLGuidance is faster on complex CFGs but requires a Rust toolchain as a second cross-compile on top of CMake+NDK (rustup target aarch64-linux-android + cargo-ndk) — a real cost for a team of two. (3) Constrained-decoding overhead is 5–15% on simple schemas; our schema is a handful of enum fields, not a complex CFG; the headline LLGuidance/XGrammar speedups (up to 100x) are measured on complex grammars and GPU serving, not on our case. (4) XGrammar has no native llama.cpp integration at all — a foreign ecosystem (vLLM/SGLang).
**Design rule (fixed in the schema):** enums/numbers only, NO free text. (a) Keeps the classic PDA parser fast — unlike Earley engines it suffers exponential state growth on heavily branching grammars, and free text is the most branching thing there is. (b) Removes the risk of a small model inventing an invalid human-readable string. Human-readable RU/KZ wording lives in app code (Aggregator, ADR-005) as templates keyed by `category`+`reason_code`, not generated by the model.
**Consequences:** The grammar must be kept in sync with the verdict schema (verdict_schema.json is the source of truth). Adding a new category/reason = editing the schema, the .gbnf, and the template table in code — three places, easy to forget one. If the benchmark (ADR-002/003) shows that even an enum-only grammar has unacceptable latency overhead on a 3–4 GB device — LLGuidance stays as a roadmap option, not now.

## ADR-005: Architecture — the ROMA pattern, native implementation
**Status:** Decided (role mapping formalized; the Aggregator reconciliation policy closed 07.2026 — see below). Prototype: aggregator.py.
**Context:** We need a manageable pipeline for parsing a message/call, not one monolithic prompt. By this point the following already exist: the regex layer (ADR-007), the number classifier (ADR-011), the GBNF verdict schema (ADR-004), call state/correlation (ShieldEngine, prototype call_detect.py) — ADR-005 must weld them into one architecture, not live as a separate declaration.
**Options:** Sentient's Python framework (ROMA); a native implementation of the pattern in mobile code; a home-grown ad-hoc pipeline.
**Decision:** The ROMA pattern (Atomizer → Planner → Executor → Aggregator), rewritten natively in Kotlin. The concrete mapping:
- **Atomizer** = the regex layer (ADR-007) + the number classifier (ADR-011), runs first on every event (notification/call/SMS). Decides: is the signal unambiguous ("atomic" — trustworthy OR a clearly known pattern, no LLM needed) or ambiguous (escalate further). Most traffic (ordinary bank notifications, deliveries, chat) is resolved right here — it does not wake the LLM. This is also the battery argument for the spec/pitch.
- **Planner** = state correlation (ShieldEngine from call_detect.py): holds the "active risky call" window, decides which Executors to invoke and at what priority (e.g. while the window is open — SMS/app installs are checked with heightened sensitivity).
- **Executor** (several typed ones, not one): regex matcher, number classifier, allowlist lookup (bank impersonation), the GBNF-constrained LLM call (ADR-004) via JNI into llama.cpp. Only Executors touch native code/system APIs (NLS/CallScreening/PackageAdded/UsageStats, ADR-001/011/012).
- **Aggregator** = merging Executor results into the final Verdict, mapping category/reason_code → a localized RU/KZ template (ADR-004 Consequences), handing off to accessible output (ADR-013).
**Why:** Sentient's Python stack targets server/Docker — it does not fit a phone. We take only the pattern and implement it natively. Mapping onto Kotlin coroutines: the Atomizer — synchronous/cheap on the calling thread; Planner/Executor with the LLM — on a dedicated dispatcher, off the main thread.
**Concurrency requirement (confirmed by research):** one `llama_context` for the whole app, access **serialized** (single-thread dispatcher / actor), never concurrent — llama.cpp has known segfaults on parallel inference into a shared context even on pure CPU, and a second parallel context means a second KV cache, which the RAM budget (ADR-002) does not allow. If several events need the LLM at once — they queue, they do not run in parallel.
**Aggregator reconciliation policy (closed 07.2026):** when the Atomizer/regex layer and the LLM Executor disagree (e.g. regex said `warn`, the LLM said `danger`, or vice versa).
**Decision:** `final_level = max(atomizer_level, llm_level)` — neither source can lower the result, only raise it. Category/reason_code are copied from the source whose level won (showing level=danger with a reason_code from the source that proposed warn is worse than losing the detail — field mismatch confuses). On a level tie — prefer category/reason_code from the LLM (it was invoked specifically for the ambiguous case and saw the full text; the regex candidate stays in the logs for audit, not shown to the user). Confidence — from the winning source; on a level tie take max(conf_atomizer, conf_llm).
**Why:** (1) Symmetric to "the LLM can only raise, never lower" from ADR-007 (the LLM is a secondary refinement layer) — in a two-source comparison this is mathematically the same as max(level), just with the explicit point that neither source is a "privileged suppressor" of the other. (2) Matches the precision/recall trade-off already accepted in ADR-007 for this audience: a missed scam costs more than an extra WARN. (3) The third option ("conflict → a separate 'needs manual review' level") is rejected for v1: such a level makes sense only if there is somewhere to route it — e.g. a relative via an online push, but v1 is fully offline (ADR-006/010 — the online layer is roadmap), so "needs review" would have to be shown to the elderly user themselves. That directly violates ADR-013 (output must be simple, short, imperative for an audience with reduced vision/hearing/cognition) — an ambiguous message is worse than a clear WARN. If/when an online channel to a relative appears (roadmap), "needs manual review" is worth revisiting as a separate level routed there, not to the elderly person's screen.
**Consequences:** The Aggregator must explicitly log both opinions (atomizer and llm) even when one loses — needed for debugging/audit and for the future EvoSkill-Lite (ADR-015: human review of new legends relies on knowing whether the regex systematically under- or over-estimates). Implemented in aggregator.py: a severity table by category for the "Atomizer without LLM" case (the Planner chose not to call the LLM — most traffic, ADR-005 Why) + a `reconcile()` function for when the LLM was invoked.
**Consequences:** No Python runtime on the phone. The application must phrase it honestly: "the ROMA pattern was reused", not "the Sentient framework runs on device". Adding a new Executor (e.g. a future domain/URL check, ADR-009) = just a new Executor in the pipeline; Atomizer/Planner/Aggregator are not rewritten — which is exactly the architectural reason to stick with ROMA rather than ad-hoc.

## ADR-006: Privacy — offline core separated from online features
**Status:** Decided (the trust boundary formalized as a diagram: trust_boundary.svg; the legal layer researched and added)
**Context:** Detection must work without a network; some features (push to a relative, scam-database updates) require one.
**Options:** Everything offline (unrealistic for updates/pushes); everything through a server; a core/online-layer split.
**Decision:** The offline detection core is separated from the online layer. Online features are opt-in, requiring explicit consent and a network. The boundary is formalized: see trust_boundary.svg (what exactly falls on each side, including the ADR-009/014/015 clarification — seed lists/allowlist/vocabulary baked into the APK at build time are OFFLINE; only runtime updates of that data are ONLINE).
**Why:** Lets us claim offline detection honestly, without hiding network dependencies.
**Legal layer (verified; Law of the Republic of Kazakhstan No. 94-V "On Personal Data", as amended 17.11.2025, in force since 18.01.2026):**
- **Data localization**: personal data of KZ citizens must be stored on the territory of Kazakhstan; the requirement covers any electronic resources/cloud solutions regardless of whether the company is international. Direct consequence: if we build a community server (ADR-015 roadmap) or any server side of the online layer — the server is physically in KZ; "any cloud" is not an option.
- **Cross-border transfer** (Art. 16) is allowed but requires separate explicit consent of the subject + adequate protection in the receiving country. Direct consequence for "push to a relative": if implemented via FCM (the standard Android route), data about the elderly person leaves for infrastructure outside KZ — that is a cross-border transfer and requires a **separate** consent, not a generic "enable online features" checkbox.
- Consent must structurally disclose: validity period, whether data goes to third parties, whether there is cross-border transfer, the list of collected data — that is the checklist for the online layer's onboarding screen, not a formality.
**Needs checking (not researched, flagged on the diagram):** Law of the Republic of Kazakhstan No. 256-VIII of 09.01.2026 (changes in AI and informatization, in force since 11.07.2026) — details not studied, potentially applies to an "AI shield" directly. Check before finalizing the spec; do not guess.
**Consequences:** **Wording: never "100% offline" without the opt-in online layer caveat.** Consent for "push to a relative" and consent for cross-border transfer are different screens, not one toggle. If push is needed in v1 at all — consider a push provider hosted/routed through KZ as an FCM alternative (separate research, not a blocker now).

**DEFERRED (decision of 07.2026):** The concrete online-layer implementation (push provider, server localization, cross-border consent, Law No. 256-VIII) is deliberately shelved — to be resolved separately when the grant application is being prepared, not now. Everything fixed above in this ADR (trust boundary, legal requirements) remains in force as context for the future, but does not block the spec and needs no answer right now. Focus — the offline core.

## ADR-007: Language — RU/KZ regex as the first line, LLM refines
**Status:** Decided (reconsideration 07.2026: the viability of the regex approach for Kazakh morphology checked; prototype built). Prototype: regex_layer.py.
**Context:** Small models handle Kazakh poorly; we need reliability on RU/KZ. This layer is also the Atomizer of the ROMA pipeline (ADR-005) and must confidently resolve most traffic without the LLM.
**Options:** LLM for everything; regex for everything; a regex→LLM hybrid.
**Decision:** A RU/KZ regex layer as the first line of detection (`atomize()` in regex_layer.py); the LLM is pulled in only to refine ambiguous cases. Patterns: root + `\w*` (wildcard suffix), not exact word match.
**Why:** Regex is deterministic and cheap, catches known patterns in both languages. The LLM covers the grey zones, where models are stronger in Russian than Kazakh.
**Verified (KZ morphology — previously an unchecked assumption):** Kazakh is agglutinative: the root is usually stable and suffixes attach to it (unlike fusional languages where the stem itself changes) — good news for wildcard-suffix regex. BUT: even professional Kazakh stemmers cover ~80% of word forms, not 100% — morpheme-boundary assimilation is possible (the root's last letter can change before certain suffixes). Conclusion: the regex layer on Kazakh will systematically have slightly lower recall than on Russian — expected, not a bug, and covered by LLM refinement (which is this ADR's whole point), but set the KZ quality expectations in the pitch accordingly; do not promise parity with Russian.
**Precision-vs-recall design decision:** Patterns are deliberately narrow where a root is ambiguous (e.g. Russian «перевод» means both "money transfer" and "translation") — we require a co-occurring context word (деньги/средства/сумму — money/funds/amount), not the bare root. Rationale: the Atomizer must confidently resolve benign traffic without escalation (ADR-005) — false positives cost more (they wake the LLM/alarm the user) than a borderline miss, which the LLM layer can still catch on other signals (actions, ADR-011) or resolve as a weak WARN instead of DANGER.
**Consequences:** The regex layer carries the main workload → its quality is critical, and the pattern set must grow (that is exactly what EvoSkill-Lite is, ADR-015 — controlled vocabulary extension with human review, not autonomous generation). LLM load drops (a plus for speed/battery). Category/reason_code in regex_layer.py literally match verdict_schema.json (ADR-004) — the Atomizer and the LLM Executor speak the same vocabulary, which is what lets the Aggregator reconcile them.

**Category coverage (completed 07.2026):** 8 of the 9 textual categories of the schema (ADR-004) are implemented in regex_layer.py — otp_shared_request, remote_access_install, urgency_pressure (2 variants: do-not-hang-up / money transfer), prize_lottery_scam, financial_pyramid_signal, bank_impersonation, phishing_link, romance_scam. The ninth — unknown_caller_action — is NOT a textual signal by construction; it is call state from ShieldEngine (ADR-011) and is correctly absent from the regex layer.

**Tests (checked against the code 2026-07-30).** The Python prototype `regex_layer.py` has
18 cases (11 positive + 7 false-positive controls). The Kotlin port `RegexAtomizerTest`
has moved ahead: **29 cases** (19 positive + 10 controls) — the two-tier OTP and the
controls for «код» ("code") in ordinary chat were added
([ENGINEERING_NOTES §3.2](ENGINEERING_NOTES.md)). `:core` has **62 tests** total (some
via `@TestFactory`, so JUnit counts every case as a separate test). Python remains the
behavioural reference: change the regex in Kotlin — sync the prototype.
- **bank_impersonation**: the textual signal is independent of the number check (that lives in ShieldEngine/allowlist, ADR-011) — here we catch a bank mention + an alarming phrase nearby ("заблокирована"/blocked, "служба безопасности"/security service). Deliberately does not trigger on a bare brand mention (otherwise "Kaspi delivered your order" would be a false alarm).
- **phishing_link**: an architecturally special category — the Atomizer does not judge phishing itself here; it only signals "there is a URL + an alarming context, pass to the domain-check Executor" (ADR-009 seed list). Requires an ACTUAL URL in the text, not just a "follow the link" phrase — otherwise it fires on link-less fragments.
- **romance_scam**: honestly the weakest regex coverage of all categories — the legend usually unfolds over a multi-message dialogue, not a single message; the pattern only catches the already-direct "romance + money/ticket request" combination in one message. Do not oversell it in the pitch: it is a safety net, not the main defence against this scam type.

## ADR-008: Background survival strategy — event-driven shortService, NOT a permanent FGS
**Status:** Decided (reconsideration 07.2026 #2: an event-driven model instead of a 24/7 foreground service — better UX and probably better battery, with unverified items)
**Context:** Budget OEMs (MIUI etc.) aggressively kill background work. The earlier decision was "a permanent foreground service". Revisited: NLS and CallScreeningService are both system-bound services — the system invokes their callbacks per the API contract without us keeping the process alive in advance.
**Options:** A permanent 24/7 FGS (the original ADR-008 v1); event-driven — a short `shortService` FGS raised only for the duration of processing a specific event (call/notification); a hybrid.
**Decision:** Event-driven. On an event from NLS.onNotificationPosted()/CallScreeningService.onScreenCall() — raise a `shortService` FGS only for the pipeline run (Atomizer→Planner→Executor→Aggregator, ADR-005), then `stopSelf()`. No 24/7 process/notification.
**Why:** `shortService` is not tied to a specific use case and needs no permissions beyond FOREGROUND_SERVICE. Among the exemptions from "no FGS starts from the background" is "a system component starts the service", which resembles our case (the callback is system-initiated). Much better UX: the notification appears for seconds during processing instead of hanging forever (removes part of the ADR-013 UX compromise for the elderly). Less RAM/battery — the process does not have to stay high-priority at all times.
**Needs verification before the spec relies on it (plausible per documentation, not confirmed):**
- Whether the NLS/CallScreeningService callback actually falls under the "system component starts the service" exemption — check on a build, not on paper.
- **A hard time budget: the whole pipeline must finish well under 3 minutes**, otherwise not a silent failure but an ANR (worse — it hits Play Console health metrics). This leads straight into the unresolved latency benchmark on the reference device (ADR-002/003, still open) — there is no measured confidence in model cold-load + GBNF decoding on a weak CPU.
- Whether to keep the llama.cpp model warm in memory between calls (faster, but a sizable chunk of the RAM budget permanently resident, competing with the system on a weak device) or reload per shortService (slower, safer on memory) — unresolved, depends on the benchmark.
- **The OEM threat is not removed by this model.** MIUI and friends layer their own heuristics on top of the official Android contract — on some ROMs "no permanent notification" can read as "unimportant app", and the NLS listener registration itself can be blocked by an aggressive OEM manager regardless of the service architecture. Autostart/whitelist onboarding (MIUI/Oppo/Vivo/Samsung) remains necessary either way — verify separately on real devices.
**Consequences:** The permanent notification in the shade is probably no longer a mandatory compromise (good news for ADR-013/UX) — but it is not confirmed that this works equally reliably across OEMs. The ADR-005 concurrency requirement (serialized access to llama_context) stays in force and becomes even more important here: if two events (a call + an SMS simultaneously) raise two shortServices in parallel, both contend for the same LLM Executor — an explicit queue/lock is needed, not "both start and hope".

## ADR-011: Call channel — CallScreeningService + ROLE_CALL_SCREENING
**Status:** Decided (in v1). Reconsideration 07.2026: the timing budget confirmed, an architectural inaccuracy found in the prototype, a free signal added.
**Context:** The NLS gives no useful signal from a live call; calls are the main attack vector against the elderly.
**Options:** Reading the call screen via Accessibility (rejected, ADR-001); CallScreeningService + the role; nothing.
**Decision:** CallScreeningService + the ROLE_CALL_SCREENING role. Becoming the default "Phone" app is NOT needed.
**Why:** "Caller ID, spam detection and blocking" is a permitted exception in Play's call-permission policy. The sanctioned path (Truecaller / Google Call Screen live on it).
**Verified — a hard timing budget:** `onScreenCall()` must call `respondToCall()` within **5 seconds** — the phone does not start ringing until a response arrives or the timeout expires. Our current design (regex/dict number checks only, no LLM at this stage) fits with a wide margin by construction — the LLM does not participate in call screening at all, which confirms the architecture (ADR-005) was designed right rather than lucky.
**Architectural correction (found during the check):** the `claim_text` parameter in the prototype's `on_call_started()` (call_detect.py) is physically unavailable at the `onScreenCall()` stage — only the number is known; the call has not been answered, nobody has said anything yet. The check "claims to be bank X, but the number is not X's" CANNOT run during screening — it arrives later, through another channel (a bank mention in a parallel SMS, category `bank_impersonation` in regex_layer.py, ADR-007) and is attached to the same call-state window (ShieldEngine), not checked inside the screening itself.
**New signal (previously unused, available for free):** `Call.Details.getCallerNumberVerificationStatus()` — the carrier's network verification of the number (PASSED/FAILED/NOT_VERIFIED), STIR/SHAKEN-grade. FAILED is a strong spoofed-number indicator, independent of our own classification heuristics. Added to the number classifier as extra risk weight, not replacing the existing logic.
**Consequences:** Gives the caller's **number** (caller ID), NOT the conversation content. Catching scam by number → runs into ADR-009 (no open scam-number database for KZ). In v1 it works on heuristics (unknown number / odd prefix / not in contacts) + user reports, not on a "database". Call audio is unreadable — speech recognition + call recording is a separate beast with legal landmines, out of v1. One call-screening app per system.

**The detection model (prototype: call_detect.py):**
Call audio is unavailable (closed since Android 10, recording banned by Play) → we detect along two axes:
- *The number = a weak context signal, NOT a verdict.* The classifier: KZ (`+7 7xx`, own operator subprefix list) / RU mobile (`+7 9xx`) / RU fixed / foreign / hidden / shortcode / alphabetic sender. Polarity matters: "+7" is NOT "clean" — impersonators spoof local numbers. A separate flag: the allowlist of official bank numbers → "claims to be bank X, but the number ≠ official X".
- *Actions during the call = the main detection (this is where the damage happens).* The "risky call" window is armed on ANY call not in the contacts (including an unknown `+7`). While the window is open, we correlate with events visible in our channels: an OTP SMS (the "dictate the code" scheme), a remote-access install/launch (AnyDesk/TeamViewer/RustDesk — `PACKAGE_ADDED` + UsageStats), a bank app opening, a phishing link. Action + an active unknown call = DANGER (conf 0.85–0.92).
- *A lone unknown `+7` with no actions = a quiet INFO, not a siren* (alert fatigue otherwise). Foreign/hidden alone = WARN. Verified on a prototype run.

The essence: **the number = a context trigger, the actions = the detection.** All offline. UsageStats is a legal special permission (not a Play blocker); needs an onboarding request.

## ADR-012: SMS channel — NLS in v1, default SMS handler deferred
**Status:** Decided (v1 = NLS); the default SMS handler — conditionally deferred. Reconsideration 07.2026: the full-text extraction mechanism clarified, relevant OEM-fragmentation news found.
**Context:** We need the text of incoming SMS. Only the default-SMS-app role guarantees the full body.
**Options:** Default SMS handler (full access); the SMS notification via NLS; a Play exception without the role.
**Decision:** v1 — read SMS via the NLS (the stock Messages app puts the full text in the notification). We do NOT become the default SMS app in v1.
**Why:** Play grants SMS permissions only to the default handler or via a temporary review exception, and only if no alternative exists. An alternative exists (NLS). Default handler = taking over the user's entire SMS experience (receive/send/store/display) — heavy for a shield that installs alongside; review may also reject it as "not necessary for core".
**Clarified — the extraction mechanism (was a vague "the text is in the notification", now specific):** the NLS reads `StatusBarNotification.getNotification().extras`, where the text can live in different fields depending on the sending app's notification style: `EXTRA_MESSAGES` (MessagingStyle — a structured message array, the fullest) → `EXTRA_BIG_TEXT` (expanded view) → `EXTRA_TEXT` (the basic field; often exactly the collapsed preview line, which is where the truncation risk comes from). Extraction must walk this chain preferring the fullest field, not read `EXTRA_TEXT` alone. NOT confirmed on a build whether Google Messages uses MessagingStyle for plain SMS (not just RCS) — most likely yes, but requires a practical check.
**Found — OEM fragmentation easing:** Samsung Messages was discontinued on 6 July 2026 — Samsung is moving users to Google Messages by default. One major vendor with a non-standard SMS app drops out of the test matrix; MIUI/Oppo/Vivo with their variants remain a live task.
**Consequences:** The risk of very long SMS being truncated on some ROMs — now clear where it comes from (a wrong extras priority) and how to minimize it (the fallback chain), but not guaranteed fully closed on all OEMs. Revisit trigger: if tests show real truncation/misses even with the correct extras priority — promote the default SMS handler in the roadmap. Do NOT promise SMS capture via takeover in the application.

## ADR-013: Accessible warning output (audience's vision/hearing)
**Status:** Decided (reconsideration 07.2026: channels split by call/message context, the alert mechanism clarified)
**Context:** The audience is elderly people with impaired vision/hearing/cognition. The warning must get through.
**Options:** Become an accessibility service (rejected, ADR-001); ordinary output APIs.
**Decision:** Accessible output via ordinary APIs, no AccessibilityService hook: TextToSpeech (spoken verdict), vibration + loud sound, a full-screen alert (large font respecting the system scale, high contrast, simple text), compatibility with the user's already-enabled TalkBack.
**Why:** Disability is addressed on the OUTPUT side (how we warn), not the INPUT side (reading other apps' screens). Zero Play policy risk.
**Clarified — different channels for different situations (was lumped together, now split):**
- **During an active call**: TTS is NOT used. Reason: live call audio runs on a separate voice route with priority — TTS from another app either will not sound where the user can hear it, or (worse) will break through and be heard by the scammer via the microphone, burning the shield's detection at the critical moment. The in-call channel is vibration + visual only (no voice). Needs verification on a build (audio routing, not tested in practice).
- **For a message/SMS (no live adversary on the line)**: TTS is appropriate and safe; we voice the verdict.
**Clarified — the full-screen alert mechanism:** `USE_FULL_SCREEN_INTENT` is preferred (like an incoming call/alarm), NOT `SYSTEM_ALERT_WINDOW` (overlay). Since Android 14 FSI is by default available only to calling/alarm apps — we do hold a call role (ROLE_CALL_SCREENING, ADR-011), which argues for FSI but does not guarantee it; must be verified on a build specifically for the call-screening role (not just the default Phone app). `SYSTEM_ALERT_WINDOW` is deliberately not taken: that permission is a signature malware-pattern marker (banking trojans/ransomware/adware use it massively), and our permission set (NLS+CallScreening+PackageAdded/UsageStats) is already eyebrow-raising for Play's automated review — adding an overlay on top produces the same permission profile as a banking trojan, needless review risk.
**Consequences:** The UX requirements for alert design (contrast/size/voice-over) — a separate spec section. Warning texts — short, imperative, RU/KZ. Different output channels for call/message mean two scenarios in the alert design, not one universal — account for it in mock-ups.

## ADR-014: The project's open component (a Sentient grant requirement)
**Status:** Decided
**Context:** Sentient's criterion: "at least one essential part — model, weights, code, data, or evals — is something anyone can run, inspect, and build on, and that openness is why the product is better". The model (Qwen/Llama) is someone else's open weights, not our openness story. We need our own answer.
**Options:** Open the whole app code; open only the phrasing regex layer; open the regex layer + the normalized indicator schema + the domain/URL aggregator pipeline (tied to ADR-009).
**Decision:** We open the RU/KZ scam-phrasing regex layer (ADR-007) + the indicator schema + the aggregator pipeline (the ADR-009 research prototype) as a public dataset/tooling. Not the entire app code (detection logic and prompts are the working product and do not have to be open to meet the criterion).
**Why:** A RU/KZ scam-pattern dataset for small models simply does not exist — exactly the "long tail, no vendor has market reason to collect" public good Sentient explicitly names as desirable (Part Two, item 13). It simultaneously turns the unresolved ADR-009 blocker (no database source) into an asset of the application: we are not hiding the data gap, we are building and opening the missing piece.
**Consequences:** ~~A license for the published dataset/schema is needed~~ — **DONE 2026-07-27:** the repository is published under **Apache-2.0** ([LICENSE](LICENSE), [NOTICE](NOTICE)); third-party components and their licenses are covered in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) (including verifying that the Qwen2.5-0.5B weights are also Apache-2.0 and llama.cpp is MIT). If the pattern dataset is later split into its own repository, CC-BY suits data better — decide at split time. Community-contribution moderation is needed (people may submit low-quality/malicious patterns — a review process; not a blocker for the v1 application, but a roadmap item). In the pitch — a dedicated "our open part" slide, not a passing mention.

## ADR-015: Adapting to new scam legends — regex vocabulary extension (EvoSkill-Lite), NOT self-modifying grammar
**Status:** Decided (reconsideration 07.2026 #2: checked against the real repository github.com/sentient-agi/EvoSkill — the mechanism description below corrected, the decision (b) itself confirmed)
**Context:** Scammers rotate legends ("from eGov" → "from the KNB" → "a parcel from Kazpost"); static patterns age. A proposal inspired by the name "EvoSkill" was considered — checking against Sentient's real open-source tool showed the description below (option a) does not match it, see "Corrected" after Why.
**Options:** (a) The autonomous scenario originally attributed to "EvoSkill": the on-device model generates new rules itself, rewrites the GBNF grammar and ships them to a server for distribution to all users (community tuning); (b) EvoSkill-Lite — extending the regex layer's vocabulary (ADR-007) with new keywords/legends, human-reviewed, the verdict schema (ADR-004) unchanged; (c) no adaptation at all, manual app updates only.
**Decision:** (b) EvoSkill-Lite. The verdict schema (level/category/reason_code, ADR-004) is fixed and does not mutate on device. New legends are added as keywords mapping onto existing category/reason_code — the taxonomy is already abstracted from the cover-brand ("parcel+code" and "eGov+code" are both `otp_shared_request`/`asks_otp_code`); a new cover organization needs no new category.
**Why (why NOT option (a), rejected both for v1 and as an application promise):**
- Direct contradiction of the ADR-004 design rule (enums/numbers only, no free-form structure generation by the model) — self-modifying grammar destroys the single guarantee GBNF was adopted for.
- A 0.5–1B model is not fit for few-shot rule induction (a different complexity class from classification; our own ADR-003 benchmarks show quality degradation on knowledge tasks already, and rule synthesis is harder and riskier).
- An open data-poisoning channel: an attacker can deliberately feed patterns that autonomous tuning learns as benign.
- The "relative pressed 'not spam'" feedback loop conflicts with ADR-013: for an audience with motor/vision impairments an accidental mis-tap silently weakens the protection without confirmation.
- A community server (log collection + moderation + distribution) is separate infrastructure, out of scope for a two-person grant team; it replays the unresolved ADR-009 problem (who verifies the source), now additionally noised by LLM hallucinations.
**Corrected (07.2026, checked against github.com/sentient-agi/EvoSkill):** Option (a) was originally described as "the EvoSkill proposal" — inaccurate; Sentient's real tool is nothing like it. The actual EvoSkill is a dev-time CLI/Python API for automatically improving CODING agents (Claude Code, Codex CLI, OpenCode, Goose, OpenHands) against a benchmark (a CSV or a Harbor question→reference dataset): a Proposer→Generator→Evaluator loop writes/edits markdown skill files (`.claude/skills/*/SKILL.md`) or the system prompt; every iteration is a separate git branch (`program/iter-skill-N`), diffable against baseline via `evoskill diff`. It runs on the developer's machine or in a Docker/Daytona sandbox — NOT on the end user's device, and it does not auto-distribute changes to users; human review of every iteration is built into the tool's own mechanics (git branches + diff), not something to be added on top. None of that describes option (a) — that was a hypothetical scenario (on-device autonomous mutation + cloud push to all users) unrelated to the real tool of that name. The decision (b) stands — the Why arguments hold for scenario (a) regardless of its name — but **the pitch must not say "we considered and rejected EvoSkill"** if the real Sentient tool is meant: a reviewer who knows their own product will spot the mismatch. The correct phrasing — we considered a hypothetical autonomous self-tuning scenario and rejected it in favour of a controlled manual process; separately, the real EvoSkill is a potential dev-time tool for BUILDING that controlled process (see the roadmap idea below), not the thing that was rejected.
**Roadmap idea (not decided; needs its own evaluation; does not block decision (b)):** Since the real EvoSkill is a git-diff-reviewable dev-time loop, it can be aimed not at a coding agent's markdown skills but at `regex_layer.py` itself: define a benchmark (a set of RU/KZ scam texts with expected categories, modelled on the tests in `regex_layer.py`) and let the Proposer/Generator loop propose regex-pattern edits that the developer merges manually from the diff — i.e. replace hand-inventing keywords (EvoSkill-Lite as a process) with literally reusing Sentient's open-source tool as a candidate generator. Stronger for the pitch ("we don't just open our dataset, ADR-014 — we use the grantor's tool to grow it"), but unevaluated in practice (need to check the harness can handle "fix Python regexes from classification failures" — not the same as "fix a coding task").
**Consequences:** Regex vocabulary updates ship via app updates (v1) or a signed config file through the online layer (roadmap, opt-in, ADR-006), in both cases with human review before publication. In the pitch phrase it as "the taxonomy is robust to legend changes; the vocabulary grows in a controlled way" — honest, and it still demonstrates the same advantage ("scammers rewrite scripts — we do not rewrite the core") without promising autonomous self-learning the team can neither build in time nor safely defend against data poisoning.

## ADR-009: Source of the scam domain/number database — **OPEN (narrowed)**
**Status:** Open, but with a measured picture. A bottleneck, not "just a database".
**Context:** Regex catches phrasing patterns; domain/number blacklists have to come from somewhere. Research done (below).

**The measured source picture:**

*Domains/URLs — open sources exist, but KZ coverage is negligible:*
- Phishing.Database (GitHub, MIT) — raw txt, daily, can be baked into the build and redistributed. MEASURED: 391,603 domains total, of which `.kz` — 108 (0.03%), some of them noise/legitimate sites. Global, not about KZ.
- URLhaus (abuse.ch) — CSV/JSON, free fair-use, Auth-Key; has a TLD feed (.kz). But it is malware URLs, not social engineering; commercial use → paid.

*KZ official (financial scam) — scrapeable, NOT CSV/API:*
- AFM (Financial Monitoring Agency) — the financial-pyramid list + verdicts + "Cybernadzor" (~4,000 blocked sites), a Telegram bot.
- ARDFM — the list of unscrupulous organizations + the license registry (for whitelisting).
- KZ-CERT (cert.gov.kz) — phishing advisories, NO open feed/API.
- Catches: legal greyness of republication; "suspected" entries → defamation risk when bundled.

*Phone numbers — no open KZ source exists.* Yandex caller-ID is proprietary; manual bots; RU community lists are unreliable/defamation-prone. Precisely the phone attack line (calls/SMS "bank security service") is not covered by any database.

**Proposed v1 strategy (pending scope confirmation, ADR-010):**
1. Phone/SMS: no number database — behavioural regex patterns only (ADR-007 gets reinforced).
2. Domains/URLs: Phishing.Database (MIT) baked statically into the offline core as an *additional* seed + optional online updates. Not the main layer.
3. KZ official lists (AFM/ARDFM) — roadmap: scrape + dedup + legal review.

**Remaining to decide:** ~~confirm items 1–3 as the decision~~ — CONFIRMED via ADR-010 (v1 = purely offline; items 1–3 resolved as-is).

## ADR-010: Scope of online features in v1 (MVP boundary) — **Decided by deferral**
**Status:** Decided (settled by the 07.2026 ADR-006 reconsideration) — v1 = purely offline detection.
**Context:** The online layer (push to a relative, database updates) is opt-in. The question was: which of it goes into v1 and which into the roadmap.
**Options:**
- v1 = purely offline detection (the grant demo), the whole online layer in the roadmap
- v1 = offline detection + push to a relative, database updates in the roadmap
- v1 = the full layer
**Decision:** The first option. v1 = the offline core in full (ADR-001/002/003/004/005/007/008/011/012/013), the online layer entirely in the roadmap. The concrete online implementation (push provider, server localization, cross-border-transfer consent) is deliberately not being decided now — see ADR-006 "DEFERRED" — it will be settled separately when the grant application is prepared.
**Why:** Matches the already-accepted ADR-009 proposal (no number database in v1, seed domains baked into the APK, KZ official lists in the roadmap) — the MVP boundary is the same everywhere: everything baked at build time, nothing phones home. Simplifies the grant demo (no need to explain the online layer's legal nuances before the layer itself is designed) and removes the risk of half-built online functionality leaking into the application's promises.
**Consequences:** ADR-009 items 1–3 (regex without a number database, the Phishing.Database seed baked statically, KZ lists in the roadmap) — confirmed as resolved, not "pending". The trust-boundary diagram (trust_boundary.svg) reflects this: the entire right-hand (online) block is outside v1.
