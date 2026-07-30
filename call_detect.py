"""
Невидимый Щит — прототип звонкового детекта (ADR-011).

Модель: содержание звонка мы НЕ читаем (аудио звонка закрыто с Android 10,
запись запрещена Play). Поэтому звонок детектим по двум осям:
  1) НОМЕР — слабый контекст-сигнал (класс + импперсонация банка).
  2) ДЕЙСТВИЯ во время звонка — основной детект (OTP, remote-access, банк-апп, ссылка).
Деньги уходят через действия, не через слова -> слой действий и есть защита.

Всё офлайновое. Никаких сетевых вызовов.

Источник KZ-префиксов: план нумерации РК (Altel 700/708, Kcell 701/702/775/778,
Beeline 705/771/776/777, izi 706, Tele2 707/747; 703/704/709 — резерв сотовых;
75x/76x — Казахтелеком фикс/данные). RU-мобилы: +7 9xx.
"""

import re
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


# ------------------------------------------------------------------ номер

KZ_MOBILE_PREFIXES = {
    "700", "701", "702", "703", "704", "705", "706", "707", "708", "709",
    "747", "771", "775", "776", "777", "778",
}
KZ_KTC_PREFIXES = {"750", "751", "760", "761", "762", "763", "764"}  # Казахтелеком/фикс-данные

REMOTE_ACCESS_PKGS = {
    "com.anydesk.anydeskandroid": "AnyDesk",
    "com.teamviewer.quicksupport.market": "TeamViewer QuickSupport",
    "com.teamviewer.host.market": "TeamViewer Host",
    "com.rustdesk.rustdesk": "RustDesk",
    "com.sand.airdroid": "AirDroid",
    "com.aweray.remote": "AweSun",
    "com.microsoft.rdc.androidx": "MS Remote Desktop",
}


class NumClass(Enum):
    KZ_MOBILE = "kz_mobile"
    KZ_FIXED = "kz_fixed"           # Казахтелеком / гео KZ
    RU_MOBILE = "ru_mobile"
    RU_FIXED = "ru_fixed"
    FOREIGN = "foreign"
    HIDDEN = "hidden"              # скрытый/withheld
    SHORTCODE = "shortcode"        # короткий сервисный номер
    ALPHA_SENDER = "alpha_sender"  # буквенный отправитель (в SMS)
    MALFORMED = "malformed"


# базовый вес класса как СЛАБЫЙ сигнал (0..1). Не приговор.
CLASS_BASE_RISK = {
    NumClass.KZ_MOBILE: 0.05,
    NumClass.KZ_FIXED: 0.05,
    NumClass.RU_MOBILE: 0.20,
    NumClass.RU_FIXED: 0.20,
    NumClass.FOREIGN: 0.35,
    NumClass.HIDDEN: 0.45,
    NumClass.SHORTCODE: 0.10,
    NumClass.ALPHA_SENDER: 0.15,
    NumClass.MALFORMED: 0.30,
}


def normalize(raw: str) -> str:
    """Грубая нормализация к нац. виду: '8XXXXXXXXXX'/'+7...' -> '7XXXXXXXXXX'."""
    if raw is None:
        return ""
    s = raw.strip()
    if s == "" or s.lower() in {"unknown", "private", "withheld", "restricted", "anonymous"}:
        return "HIDDEN"
    if re.search(r"[a-zA-Zа-яА-Я]", s):
        # буквенный sender ID (напр. "HalykBank")
        return "ALPHA:" + s
    digits = re.sub(r"\D", "", s)
    if not digits:
        return "HIDDEN"
    if digits.startswith("8") and len(digits) == 11:
        digits = "7" + digits[1:]
    if s.startswith("+7") or (digits.startswith("7") and len(digits) == 11):
        return digits
    return digits  # прочее — оставляем как есть, класс решит


def classify_number(raw: str) -> NumClass:
    n = normalize(raw)
    if n == "HIDDEN":
        return NumClass.HIDDEN
    if n.startswith("ALPHA:"):
        return NumClass.ALPHA_SENDER
    if 3 <= len(n) <= 6:
        return NumClass.SHORTCODE
    if len(n) == 11 and n.startswith("7"):
        nsn = n[1:]          # национальный значимый номер, 10 цифр
        area = nsn[:3]
        lead = nsn[0]
        if area in KZ_MOBILE_PREFIXES:
            return NumClass.KZ_MOBILE
        if area in KZ_KTC_PREFIXES:
            return NumClass.KZ_FIXED
        if lead == "7":       # прочие 7xx в зоне +7 -> гео Казахстан
            return NumClass.KZ_FIXED
        if lead == "9":       # +7 9xx -> мобильный РФ
            return NumClass.RU_MOBILE
        if lead in {"3", "4", "8"}:
            return NumClass.RU_FIXED
        return NumClass.MALFORMED
    if 7 <= len(n) <= 15:     # похоже на международный E.164, но не +7
        if n.startswith("7"):
            return NumClass.MALFORMED
        return NumClass.FOREIGN
    return NumClass.MALFORMED


# ------------------------------------------------------ импперсонация банка

# мини-allowlist официальных номеров (сорсится у самих банков; тут пример).
BANK_OFFICIAL = {
    "halyk":  {"77777", "75007777777"},   # напр. короткий 7777 + горячая линия
    "kaspi":  {"77777"},
}
# альтернативные написания брендов в тексте
BANK_ALIASES = {
    "halyk": ["halyk", "халык", "народный банк", "халық"],
    "kaspi": ["kaspi", "каспи", "каспий"],
}


def claimed_bank(text: str) -> Optional[str]:
    t = (text or "").lower()
    for bank, aliases in BANK_ALIASES.items():
        if any(a in t for a in aliases):
            return bank
    return None


def impersonation_flag(text: str, raw_number: str) -> Optional[str]:
    """Если сообщение/звонок 'от банка X', но номер не из официальных X -> флаг."""
    bank = claimed_bank(text)
    if not bank:
        return None
    # Пустой отправитель = НЕТ данных о номере (напр. текстовый канал без адреса
    # отправителя), а НЕ «скрытый номер» — без номера импперсонацию-по-номеру
    # утверждать нельзя, иначе любое упоминание банка = ложный флаг.
    if not raw_number:
        return None
    n = normalize(raw_number)
    if n in {"HIDDEN"} or n.startswith("ALPHA:"):
        return f"'Представляется как {bank}', но номер скрыт/буквенный — банки так не звонят"
    if n not in BANK_OFFICIAL.get(bank, set()):
        return f"'Представляется как {bank}', но номер {n} НЕ из официальных номеров {bank}"
    return None


# --------------------------------------------------------- OTP в сообщении

OTP_RE = re.compile(
    r"(код|kod|code|растау|пароль)\D{0,20}\b(\d{4,8})\b"
    r"|\b(\d{4,8})\b\D{0,20}(никому|ешкімге|не сообщай|не говори)",
    re.IGNORECASE,
)


def sms_has_otp(text: str) -> bool:
    return bool(OTP_RE.search(text or ""))


# ------------------------------------------------------- корреляция событий

class Level(Enum):
    NONE = 0
    INFO = 1
    WARN = 2
    DANGER = 3


@dataclass
class Verdict:
    level: Level
    confidence: float
    reasons: list = field(default_factory=list)
    advice: str = ""


# Окно корреляции «рискованного звонка». CallScreeningService на Android НЕ
# получает колбэк окончания звонка -> on_call_ended() там не вызывается, поэтому
# окно самозакрывается по таймауту (иначе висело бы вечно и любая OTP-SMS после
# первого неизвестного звонка навсегда была бы DANGER). Эвристический дефолт.
RISKY_WINDOW_SECONDS = 10 * 60


@dataclass
class CallState:
    number: str = None
    num_class: NumClass = None
    known_contact: bool = False
    active: bool = False
    risky: bool = False   # неизвестный/флагнутый номер => окно повышенной чувствительности
    started_at: float = 0.0  # момент старта окна (для самозакрытия по таймауту)


class ShieldEngine:
    """Держит состояние 'идёт рискованный звонок' и коррелирует с действиями."""

    def __init__(self, contacts=None):
        self.contacts = set(normalize(c) for c in (contacts or []))
        self.call = CallState()

    # --- звонок ---
    def on_call_started(self, raw_number: str, verification_status: str = "not_verified") -> Verdict:
        """verification_status: 'passed' | 'failed' | 'not_verified' — из
        Call.Details.getCallerNumberVerificationStatus() (STIR/SHAKEN-уровня,
        бесплатный сигнал из платформы). ВАЖНО: claim_text здесь принципиально
        недоступен — onScreenCall() вызывается ДО того как звонок принят,
        никто ничего ещё не сказал. Проверка bank_impersonation по факту
        произойдёт позже через on_sms(), если во время этого звонка придёт
        SMS с упоминанием банка (см. ADR-011 'архитектурная поправка')."""
        cls = classify_number(raw_number)
        known = normalize(raw_number) in self.contacts
        # ВАЖНО: окно армится на ЛЮБОЙ звонок не из контактов — неизвестный
        # локальный +7 это главная маскировка импперсонатора. Класс номера
        # влияет только на базовый уровень тревоги самого звонка (ниже).
        self.call = CallState(
            number=normalize(raw_number), num_class=cls,
            known_contact=known, active=True,
            risky=(not known),
            started_at=time.time(),
        )
        reasons, level, conf = [], Level.NONE, 0.0
        if known:
            return Verdict(Level.NONE, 0.0, ["Номер в контактах"], "")

        base = CLASS_BASE_RISK[cls]
        if verification_status == "failed":
            reasons.append("Сетевая верификация номера НЕ пройдена (возможен спуфинг)")
            level, conf = Level.WARN, max(base, 0.5)
        elif base >= 0.30:            # foreign / hidden / malformed — заметный сигнал
            reasons.append(f"Неизвестный номер, класс: {cls.value}")
            level, conf = Level.WARN, base
        else:                          # kz / ru-моб / shortcode — тихий флажок, НЕ сирена
            reasons.append(f"Неизвестный номер (класс {cls.value}) — слабый сигнал")
            level, conf = Level.INFO, base

        advice = ("Идёт звонок с незнакомого номера. Настоящий банк/госорган "
                  "никогда не просит код из СМС и не требует переводить деньги "
                  "«на безопасный счёт».")
        return Verdict(level, conf, reasons, advice)

    def on_call_ended(self):
        self.call.active = False
        self.call.risky = False

    # --- действия во время звонка (основной слой) ---
    def _during_risky_call(self) -> bool:
        return (self.call.active and self.call.risky
                and (time.time() - self.call.started_at) <= RISKY_WINDOW_SECONDS)

    def on_sms(self, text: str, raw_sender: str = "") -> Verdict:
        otp = sms_has_otp(text)
        imp = impersonation_flag(text, raw_sender)
        reasons = []
        level, conf = Level.NONE, 0.0

        if otp and self._during_risky_call():
            reasons.append("OTP/код в СМС пришёл ВО ВРЕМЯ звонка с незнакомого номера")
            reasons.append("Классическая схема «продиктуйте код из сообщения»")
            level, conf = Level.DANGER, 0.9
        elif otp:
            reasons.append("Пришёл код подтверждения — никому его не сообщайте")
            level, conf = Level.WARN, 0.4
        if imp:
            reasons.append(imp)
            level = Level.DANGER
            conf = max(conf, 0.8)
        if level == Level.NONE:
            return Verdict(Level.NONE, 0.0, [], "")
        advice = "НЕ называйте код никому по телефону. Положите трубку и перезвоните в банк сами."
        return Verdict(level, conf, reasons, advice)

    def on_app_installed(self, pkg: str) -> Verdict:
        name = REMOTE_ACCESS_PKGS.get(pkg)
        if not name:
            return Verdict(Level.NONE, 0.0, [], "")
        if self._during_risky_call():
            return Verdict(
                Level.DANGER, 0.92,
                [f"Во время звонка с незнакомого номера ставится программа "
                 f"удалённого доступа ({name})",
                 "Схема «установите приложение, мы поможем» = передача контроля мошеннику"],
                "СТОП. Не устанавливайте это по просьбе звонящего. Положите трубку.",
            )
        return Verdict(
            Level.WARN, 0.5,
            [f"Устанавливается программа удалённого доступа ({name})"],
            "Если это по просьбе звонящего/из СМС — не продолжайте.",
        )

    def on_bank_app_opened(self) -> Verdict:
        if self._during_risky_call():
            return Verdict(
                Level.DANGER, 0.85,
                ["Банковское приложение открыто во время звонка с незнакомого номера",
                 "Мошенник может диктовать шаги перевода"],
                "Не выполняйте операции по инструкции звонящего. Завершите звонок.",
            )
        return Verdict(Level.NONE, 0.0, [], "")


# --------------------------------------------------------------- демо

def show(title, v: Verdict):
    bar = {Level.NONE: "·", Level.INFO: "ℹ", Level.WARN: "▲", Level.DANGER: "⛔"}[v.level]
    print(f"\n{bar} [{v.level.name}] {title}  (conf={v.confidence:.2f})")
    for r in v.reasons:
        print(f"    - {r}")
    if v.advice:
        print(f"    → {v.advice}")


if __name__ == "__main__":
    print("=" * 68)
    print("КЛАССИФИКАЦИЯ НОМЕРА (слабый контекст-сигнал, не приговор)")
    print("=" * 68)
    for raw in ["+7 701 234 5678", "8 777 000 11 22", "+7 912 345 67 89",
                "+7 495 123 45 67", "+234 802 000 1111", "unknown",
                "1414", "HalykBank", "+7 727 250 00 00"]:
        print(f"  {raw:20s} -> {classify_number(raw).value:12s} "
              f"(риск {CLASS_BASE_RISK[classify_number(raw)]:.2f})")

    print("\n" + "=" * 68)
    print("СЦЕНАРИИ (номер = контекст, действия = детект)")
    print("=" * 68)

    # 1. Wangiri — зарубеж, один звонок, без действий
    eng = ShieldEngine(contacts=["+7 701 111 2233"])
    show("Зарубежный звонок (Wangiri), без действий",
         eng.on_call_started("+234 802 000 1111"))
    eng.on_call_ended()

    # 2. Неизвестный +7 сам по себе — доказываем, что +7 НЕ = «чисто»
    eng = ShieldEngine(contacts=["+7 701 111 2233"])
    show("Неизвестный +7 KZ-номер, без действий (не сирена!)",
         eng.on_call_started("+7 708 555 4433"))

    # 3. Неизвестный звонок + OTP «продиктуйте код»
    eng = ShieldEngine()
    eng.on_call_started("+7 700 999 8877")
    show("OTP-СМС во время звонка с незнакомого",
         eng.on_sms("Ваш код подтверждения 4821, никому не сообщайте"))

    # 4. Неизвестный звонок + установка AnyDesk
    eng = ShieldEngine()
    eng.on_call_started("+7 705 111 2222")
    show("Установка remote-access во время звонка",
         eng.on_app_installed("com.anydesk.anydeskandroid"))

    # 5. Импперсонация: во время звонка с незнакомого приходит SMS "от Halyk"
    #    с номера не из официальных — ПОПРАВКА ADR-011: claim_text недоступен
    #    в onScreenCall(), импперсонация ловится через SMS-канал (on_sms),
    #    а не через сам звонок.
    eng = ShieldEngine()
    eng.on_call_started("+7 747 000 1234")
    show("SMS 'от Halyk' во время звонка с незнакомого, номер SMS не из официальных",
         eng.on_sms("Здравствуйте, служба безопасности Halyk, ваша карта заблокирована", "+7 747 000 1234"))

    # 6. Звонок от контакта — тишина
    eng = ShieldEngine(contacts=["+7 701 111 2233"])
    show("Звонок от известного контакта",
         eng.on_call_started("+7 701 111 2233"))
