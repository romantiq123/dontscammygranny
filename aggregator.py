"""
Невидимый Щит — Aggregator (ADR-005), сведение Atomizer + LLM-Executor в Verdict.

Политика согласования (ADR-005, "Политика согласования Aggregator", закрыта 07.2026):
  final_level = max(atomizer_level, llm_level) — ни один источник не может
  ПОНИЗИТЬ итог, только повысить. category/reason_code берутся из источника,
  чей level победил (не смешиваем level одного источника с reason_code
  другого — рассинхрон хуже потери детали). При равенстве level предпочитаем
  category/reason_code от LLM (он видел полный текст и вызывался специально
  ради неоднозначного случая); regex-кандидат в этом случае остаётся в
  reasons/логах для аудита, не в итоговом поле.

  Третий вариант из ADR ("конфликт -> отдельный уровень требует ручной
  проверки") сознательно НЕ реализован: адресовать его некому — v1 полностью
  офлайн (ADR-006/010), нет онлайн-канала к родственнику, а показывать
  неоднозначный "требует проверки" самому пожилому пользователю нарушает
  ADR-013 (вывод должен быть простым и императивным).

Aggregator работает в двух режимах:
  1) reconcile(atom, llm_verdict=None) — Planner решил не звать LLM (большинство
     трафика, ADR-005 Why). Итог — severity по таблице CATEGORY_SEVERITY,
     откалиброванной по аналогии со звонковым каналом (call_detect.py), чтобы
     оба канала (звонок/сообщение) говорили на одной шкале Level/confidence.
  2) reconcile(atom, llm_verdict={...}) — LLM был вызван (ADR-004 GBNF-схема),
     его JSON-вердикт мержится с regex-кандидатом по правилу выше.

Человекочитаемые RU-формулировки advice — по category, не генерируются
моделью (ADR-004 Consequences). KZ-шаблоны — следующий шаг, не в этом файле.
"""

from typing import Optional

from call_detect import Level, Verdict
from regex_layer import AtomResult, atomize


# ---------------------------------------------------- severity без LLM
# Базовый level/confidence для текстовой категории, когда Atomizer был
# "atomic" и Planner не стал звать LLM. Откалибровано по аналогии с
# call_detect.py (напр. otp "в одиночку" там же = WARN 0.4), чтобы звонковый
# и текстовый каналы не расходились по шкале на одинаково уверенных сигналах.
#
# unknown_caller_action сюда не входит — это не текстовый сигнал (ADR-007),
# он формируется в ShieldEngine (call_detect.py), Aggregator текста его не видит.
CATEGORY_SEVERITY: dict[str, tuple[Level, float]] = {
    "none": (Level.NONE, 0.0),
    "otp_shared_request": (Level.WARN, 0.4),
    "remote_access_install": (Level.WARN, 0.5),
    # bank_impersonation здесь — это УЖЕ подтверждённое сочетание "упомянут
    # банк + тревожная фраза рядом" (regex_layer.py), не голое упоминание
    # бренда — поэтому уверенность выше, ближе к call_detect.py impersonation_flag.
    "bank_impersonation": (Level.DANGER, 0.8),
    "urgency_pressure": (Level.WARN, 0.5),
    "financial_pyramid_signal": (Level.WARN, 0.4),
    "prize_lottery_scam": (Level.WARN, 0.4),
    # phishing_link: regex подтвердил URL + тревожный контекст, но НЕ репутацию
    # домена (это отдельный Executor, ADR-009, seed-лист) — сознательно
    # консервативнее, не DANGER, пока домен не проверен.
    "phishing_link": (Level.WARN, 0.4),
    # romance_scam — самое слабое покрытие regex'ом по recall (ADR-007), но
    # то, что ловится, это уже прямая комбинация "романтика + деньги" в одном
    # сообщении — довольно специфичный сигнал. Чуть ниже уверенность, чем у
    # остальных WARN, отражает именно эту неуверенность конструкции паттерна.
    "romance_scam": (Level.WARN, 0.35),
}

ADVICE_BY_CATEGORY: dict[str, str] = {
    "none": "",
    "otp_shared_request": "Никому не называйте код из СМС. Банк и госорганы его не спрашивают.",
    "remote_access_install": "Не устанавливайте программы удалённого доступа по чужой просьбе.",
    "bank_impersonation": "Настоящий банк не звонит с требованием сообщить данные карты. Положите трубку и перезвоните сами по номеру с карты/банковского приложения.",
    "urgency_pressure": "Не спешите. Настоящие организации не требуют решить всё «прямо сейчас».",
    "financial_pyramid_signal": "Гарантированный доход без риска — так не бывает.",
    "prize_lottery_scam": "Если приз требует сначала заплатить или сообщить данные карты — это обман.",
    "phishing_link": "Не переходите по ссылке и не вводите данные карты.",
    "romance_scam": "Незнакомец, который просит деньги, — это обман, даже если общение кажется тёплым.",
}


LEVEL_FROM_STR = {"none": Level.NONE, "info": Level.INFO, "warn": Level.WARN, "danger": Level.DANGER}


def _atomizer_verdict(atom: AtomResult) -> Verdict:
    level, conf = CATEGORY_SEVERITY.get(atom.category, (Level.NONE, 0.0))
    reasons = list(atom.matched_ru) + list(atom.matched_kz) if atom.category != "none" else []
    return Verdict(level, conf, reasons, ADVICE_BY_CATEGORY.get(atom.category, ""))


def reconcile(atom: AtomResult, llm_verdict: Optional[dict] = None) -> Verdict:
    """Сводит Atomizer-кандидата и (опционально) LLM-JSON-вердикт (ADR-004 схема)
    в один Verdict по политике ADR-005. llm_verdict, если передан — это уже
    распарсенный JSON вида {"level": "...", "confidence": "0.x",
    "category": "...", "reason_code": "..."}.
    """
    atom_verdict = _atomizer_verdict(atom)

    if llm_verdict is None:
        return atom_verdict

    llm_level = LEVEL_FROM_STR[llm_verdict["level"]]
    llm_conf = float(llm_verdict["confidence"])
    llm_category = llm_verdict["category"]
    llm_reason = llm_verdict["reason_code"]

    audit = [
        f"Atomizer: category={atom.category} -> level={atom_verdict.level.name} (conf={atom_verdict.confidence:.2f})",
        f"LLM: category={llm_category} -> level={llm_level.name} (conf={llm_conf:.2f})",
    ]

    if llm_level.value > atom_verdict.level.value:
        winner_advice = ADVICE_BY_CATEGORY.get(llm_category, atom_verdict.advice)
        return Verdict(llm_level, llm_conf, audit + ["LLM повысил уровень — итог от LLM"], winner_advice)

    if atom_verdict.level.value > llm_level.value:
        return Verdict(
            atom_verdict.level, atom_verdict.confidence,
            audit + ["LLM предложил ниже — regex-уровень НЕ понижается (ADR-005)"],
            atom_verdict.advice,
        )

    # равенство level -> предпочитаем category/reason_code от LLM, confidence = max
    final_conf = max(atom_verdict.confidence, llm_conf)
    winner_advice = ADVICE_BY_CATEGORY.get(llm_category, atom_verdict.advice)
    return Verdict(llm_level, final_conf, audit + ["Уровни совпали — category/reason_code от LLM, confidence=max"], winner_advice)


# --------------------------------------------------------------- демо/тесты

def _show(title, v: Verdict):
    bar = {Level.NONE: "·", Level.INFO: "ℹ", Level.WARN: "▲", Level.DANGER: "⛔"}[v.level]
    print(f"\n{bar} [{v.level.name}] {title}  (conf={v.confidence:.2f})")
    for r in v.reasons:
        print(f"    - {r}")
    if v.advice:
        print(f"    → {v.advice}")


if __name__ == "__main__":
    print("=" * 68)
    print("1) Atomizer один (LLM не вызывался — большинство трафика)")
    print("=" * 68)
    atom = atomize("Ваш код подтверждения 4821, никому не сообщайте")
    v = reconcile(atom, llm_verdict=None)
    _show("OTP-текст, LLM не звали", v)
    assert v.level == Level.WARN and abs(v.confidence - 0.4) < 1e-9

    atom_none = atomize("Привет, как дела?")
    v_none = reconcile(atom_none, llm_verdict=None)
    _show("Обычная переписка, LLM не звали", v_none)
    assert v_none.level == Level.NONE

    print("\n" + "=" * 68)
    print("2) LLM ПОВЫШАЕТ уровень относительно regex")
    print("=" * 68)
    atom_weak = atomize("Ваш код подтверждения 4821, никому не сообщайте")  # regex -> WARN 0.4
    llm_escalate = {"level": "danger", "confidence": "0.9", "category": "otp_shared_request", "reason_code": "asks_otp_code"}
    v = reconcile(atom_weak, llm_escalate)
    _show("regex=WARN, LLM=DANGER -> итог DANGER", v)
    assert v.level == Level.DANGER and v.confidence == 0.9

    print("\n" + "=" * 68)
    print("3) LLM пытается ПОНИЗИТЬ regex-DANGER — regex НЕ уступает (ключевой тест ADR-005)")
    print("=" * 68)
    atom_bank = atomize("Здравствуйте, служба безопасности Halyk, ваша карта заблокирована")  # regex -> DANGER 0.8
    llm_deescalate = {"level": "warn", "confidence": "0.5", "category": "urgency_pressure", "reason_code": "urgency_act_now"}
    v = reconcile(atom_bank, llm_deescalate)
    _show("regex=DANGER, LLM=WARN -> итог остаётся DANGER (regex не понижается)", v)
    assert v.level == Level.DANGER and v.confidence == 0.8, "regex-DANGER не должен понижаться LLM"

    print("\n" + "=" * 68)
    print("4) Atomizer none, но LLM всё же вызвали (напр. активное окно риска, ADR-011) и нашли сигнал")
    print("=" * 68)
    atom_quiet = atomize("Это Айгуль, звоню по поводу заказа")
    llm_finds = {"level": "warn", "confidence": "0.6", "category": "urgency_pressure", "reason_code": "requests_money_transfer"}
    v = reconcile(atom_quiet, llm_finds)
    _show("regex=NONE, LLM=WARN -> итог WARN от LLM", v)
    assert v.level == Level.WARN and v.confidence == 0.6

    print("\n" + "=" * 68)
    print("5) Уровни совпадают — category/reason_code от LLM, confidence = max")
    print("=" * 68)
    atom_prize = atomize("Поздравляем, вы выиграли миллион тенге!")  # regex -> WARN 0.4
    llm_agree = {"level": "warn", "confidence": "0.3", "category": "prize_lottery_scam", "reason_code": "too_good_to_be_true"}
    v = reconcile(atom_prize, llm_agree)
    _show("regex=WARN(0.4), LLM=WARN(0.3) -> итог WARN, conf=max=0.4, category от LLM", v)
    assert v.level == Level.WARN and v.confidence == 0.4

    print("\n" + "ВСЕ ПРОВЕРКИ ПРОШЛИ")
