"""
Невидимый Щит — regex-слой RU/KZ (ADR-007), он же Atomizer в ROMA-пайплайне (ADR-005).

Дизайн:
- RU: корень + \\w* — русский тоже частично агглютинирует хвостами (перевод/переводи/переведите
  формально другое, но большинство нужных нам слов достаточно ловить по корню+суффиксу).
- KZ: корень + \\w* — казахский агглютинативный, корень обычно стабилен, суффиксы клеятся.
  ОГРАНИЧЕНИЕ (подтверждено ресёрчем): даже серьёзные стеммеры покрывают ~80% токенов,
  возможна ассимиляция на стыке морфем — часть словоформ уйдёт мимо. Это добирается
  LLM-слоем на неоднозначных случаях (ADR-007 why), не regex.
- Категории совпадают с verdict_schema.json (ADR-004) — Atomizer выдаёт category-кандидата,
  Aggregator и LLM-Executor работают с тем же словарём, три места не расходятся.

Namespace для regex — re.IGNORECASE не работает корректно с кириллицей без re.UNICODE
в некоторых окружениях, используем re.UNICODE | re.IGNORECASE явно.
"""

import re
from dataclasses import dataclass, field


@dataclass
class AtomResult:
    category: str          # совпадает с verdict_schema.json category enum
    reason_code: str        # совпадает с verdict_schema.json reason_code enum
    matched_ru: list = field(default_factory=list)
    matched_kz: list = field(default_factory=list)
    confident: bool = True  # True = "atomic", Planner может не звать LLM


F = re.UNICODE | re.IGNORECASE

# ---------------------------------------------------------------- OTP / код
# Два тира (ADR-007). СИЛЬНЫЕ — однозначно про "код из СМС", срабатывают сами.
RU_OTP_STRONG = [
    re.compile(r"код\w*\s+(подтвержд\w*|из\s+смс|безопасности|верификаци\w*|одноразов\w*)", F),
    re.compile(r"смс[- ]?код", F),
    re.compile(r"никому\s+не\s+(сообщ\w*|говор\w*|передав\w*)", F),
    re.compile(r"продикт\w*\s+.{0,10}код", F),   # "продиктовать" почти всегда про код
    re.compile(r"назов\w*\s+.{0,10}код", F),      # "назовите код"
]
KZ_OTP_STRONG = [
    re.compile(r"код\w*\s+(растау|қауіпсіздік)", F),
    re.compile(r"ешкімге\s+айтпа\w*", F),          # "никому не говорите"
]
# СЛАБЫЕ — "глагол + код" неоднозначен (в обычной/дев-переписке "дай код",
# "код сообщения", "кодты айт"). Срабатывают ТОЛЬКО при OTP/скам-контексте рядом
# (OTP_CONTEXT). "скажи код" сам по себе — НЕ тревога; "код скажи, доставка каспи" — да.
RU_OTP_WEAK = [
    re.compile(r"(скаж\w*|сообщ\w*|переда\w*|дай\w*|отправ\w*|напиш\w*)\s+(мне\s+)?(смс[- ]?)?код\w*", F),
    re.compile(r"код\w*\s+(скаж\w*|сообщ\w*|переда\w*|дай\w*|отправ\w*|напиш\w*)", F),
]
KZ_OTP_WEAK = [
    re.compile(r"код\w*\s+айт\w*", F),              # "кодты айтыңыз" — скажите код
    re.compile(r"код\w*\s+жібер\w*", F),            # "код жіберіңіз" — отправьте код
]
# Скам-контекст для слабых паттернов. Только сильные маркеры (не "пришёл/сообщение").
OTP_CONTEXT = re.compile(
    r"(смс|sms|otp|верификаци\w*|подтвержд\w*|растау|одноразов\w*|банк\w*|каспи|kaspi|"
    r"halyk|халы\w*|jusan|bereke|forte|доставк\w*|жеткіз\w*|посылк\w*|заблокир\w*|"
    r"бұғатта\w*|срочн\w*|жедел|счёт|счет|шот|карт[аыуеі]|whatsapp|telegram|госуслуг\w*|egov)",
    F,
)

# --------------------------------------------------- remote access install
RU_REMOTE = [
    re.compile(r"(anydesk|teamviewer|rustdesk|airdroid)", F),
    re.compile(r"установ\w*\s+(приложен\w*|программ\w*)\s+для\s+(удал[её]нн\w*|помощ\w*)", F),
    re.compile(r"дай\w*\s+доступ\s+к\s+экран\w*", F),
]
KZ_REMOTE = [
    re.compile(r"(anydesk|teamviewer|rustdesk|airdroid)", F),
    re.compile(r"қашықтан\s+бас\wар\w*\s+бағдарлам\w*", F),   # "программа удалённого управления"
    re.compile(r"экранға\s+қатынас", F),                        # "доступ к экрану"
]

# --------------------------------------------------------- urgency / нажим
RU_URGENCY = [
    re.compile(r"не\s+клад\w*\s+трубк\w*", F),
    re.compile(r"срочно\s+(перевед\w*|переведит\w*|оплат\w*)", F),
    re.compile(r"сейчас\s+же", F),
    re.compile(r"иначе\s+(заблокир\w*|потеря\w*|спишет\w*)", F),
]
KZ_URGENCY = [
    re.compile(r"тел\w*фонды\s+тастама\w*", F),      # "не кладите трубку"
    re.compile(r"жедел\s+аудар\w*", F),                # "срочно переведите"
    re.compile(r"қазір\s+(аудар\w*|төле\w*)", F),      # "сейчас переведите/оплатите"
]

# --------------------------------------------------- денежный перевод
RU_TRANSFER = [
    re.compile(r"перевед\w*\s+(деньги|средства|сумму)", F),
    re.compile(r"безопасн\w*\s+сч[её]т", F),
    re.compile(r"переведит\w*\s+на\s+карт\w*", F),
]
KZ_TRANSFER = [
    re.compile(r"ақша\w*\s+аудар\w*", F),               # "деньги переведите"
    re.compile(r"қауіпсіз\s+шот", F),                    # "безопасный счёт"
]

# ----------------------------------------------------------- приз/лотерея
RU_PRIZE = [
    re.compile(r"вы\s+выиграл\w*", F),
    re.compile(r"поздравля\w*.{0,20}(приз|розыгрыш|выигрыш)", F),
    re.compile(r"чтобы\s+получ\w*\s+приз", F),
]
KZ_PRIZE = [
    re.compile(r"сіз\s+ұтып\s+ал\w*", F),                # "вы выиграли"
    re.compile(r"жүлде\w*\s+ал\w*\s+үшін", F),           # "чтобы получить приз"
]

# ---------------------------------------------------- финансовая пирамида
RU_PYRAMID = [
    re.compile(r"пассивн\w*\s+доход\w*\s+(без\s+риск\w*|гарантир\w*)", F),
    re.compile(r"удво\w*\s+(деньги|вложени\w*|капитал\w*)", F),
    re.compile(r"инвестиру\w*\s+сегодня.{0,20}(завтра|через\s+недел\w*)", F),
]
KZ_PYRAMID = [
    re.compile(r"тәуекелсіз\s+табыс", F),                # "доход без риска"
    re.compile(r"ақшаңызды\s+екі\s+есе", F),             # "удвойте деньги"
]

# ------------------------------------------------------- bank impersonation
# Текстовый сигнал (независимо от номера — номер проверяется отдельно в
# ShieldEngine/call_detect.py через allowlist). Здесь: упоминание банка +
# тревожная формулировка про карту/счёт/операцию.
BANK_NAMES = r"(halyk|халык|халық|народны\w*|kaspi|каспи|kaspi\.kz|bereke|береке|jusan|жусан|forte|форте|eurasian|евразийск\w*)"
RU_BANK_IMPERSONATION = [
    re.compile(BANK_NAMES + r".{0,30}(служба\s+безопасности|заблокир\w*|подозрительн\w*\s+(операци\w*|транзакци\w*))", F),
    re.compile(r"(служба\s+безопасности|представитель)\s+банка", F),
    re.compile(r"ваша\s+карт\w*\s+заблокирован\w*", F),
]
KZ_BANK_IMPERSONATION = [
    re.compile(BANK_NAMES + r".{0,30}(қауіпсіздік\s+қызметі|бұғаттал\w*|күдікті\s+транзакция)", F),
    re.compile(r"қауіпсіздік\s+қызметі", F),           # "служба безопасности"
    re.compile(r"картаңыз\s+бұғаттал\w*", F),           # "ваша карта заблокирована"
]

# --------------------------------------------------------------- phishing link
# ВАЖНО: это не финальный вердикт, а сигнал "передай в domain-check Executor"
# (ADR-009, seed-лист/allowlist). Regex здесь ищет URL + тревожный контекст
# вокруг него, не оценивает репутацию самого домена — это чужая ответственность.
URL_PATTERN = re.compile(
    r"https?://\S+|(?:[a-zA-Zа-яА-Я0-9\-]+\.(?:kz|ru|com|net|org|info|xyz|top|site))\b", F
)
RU_PHISHING_CONTEXT = [
    re.compile(r"перейд\w*\s+по\s+ссылк\w*", F),
    re.compile(r"подтверд\w*\s+данны\w*\s+по\s+ссылк\w*", F),
    re.compile(r"обновит\w*\s+данны\w*\s+карт\w*", F),
]
KZ_PHISHING_CONTEXT = [
    re.compile(r"сілтеме\s+арқылы\s+өтіңіз", F),        # "перейдите по ссылке"
    re.compile(r"деректерді\s+растаңыз", F),             # "подтвердите данные"
]

# --------------------------------------------------------------- romance scam
# Слабое покрытие regex'ом по конструкции — легенда раскручивается медленно,
# через диалог, а не одно сообщение. Ловим только явную комбинацию
# "романтика/одиночество" + просьба денег/подарка/билета в одном сообщении.
RU_ROMANCE = [
    re.compile(r"(полюбил\w*|влюби\w*)\s+тебя.{0,60}(деньги|перевед\w*|билет|подарок)", F),
    re.compile(r"застрял\w*\s+(на\s+границе|в\s+аэропорту|за\s+рубежом).{0,40}(деньги|перевед\w*)", F),
]
KZ_ROMANCE = [
    re.compile(r"сені\s+сүй\w*.{0,60}(ақша|аудар\w*|билет|сыйлық)", F),   # "тебя люблю...деньги"
    re.compile(r"шекарада\s+қалып\s+қойд\w*.{0,40}(ақша|аудар\w*)", F),   # "застрял на границе"
]

# Кортеж правила: (category, reason_code, ru, kz, requires_url, context_re).
# requires_url — правило учитывается только при наличии URL (phishing_link).
# context_re — только если этот контекст тоже есть в тексте (слабый OTP: глагол+код).
RULES = [
    ("otp_shared_request", "asks_otp_code", RU_OTP_STRONG, KZ_OTP_STRONG, False, None),
    ("otp_shared_request", "asks_otp_code", RU_OTP_WEAK, KZ_OTP_WEAK, False, OTP_CONTEXT),
    ("remote_access_install", "asks_remote_access_app", RU_REMOTE, KZ_REMOTE, False, None),
    ("urgency_pressure", "urgency_do_not_hang_up", RU_URGENCY, KZ_URGENCY, False, None),
    ("urgency_pressure", "requests_money_transfer", RU_TRANSFER, KZ_TRANSFER, False, None),
    ("prize_lottery_scam", "too_good_to_be_true", RU_PRIZE, KZ_PRIZE, False, None),
    ("financial_pyramid_signal", "too_good_to_be_true", RU_PYRAMID, KZ_PYRAMID, False, None),
    ("bank_impersonation", "claims_bank_wrong_number", RU_BANK_IMPERSONATION, KZ_BANK_IMPERSONATION, False, None),
    ("phishing_link", "suspicious_link_domain", RU_PHISHING_CONTEXT, KZ_PHISHING_CONTEXT, True, None),
    ("romance_scam", "requests_money_transfer", RU_ROMANCE, KZ_ROMANCE, False, None),
]


def has_url(text: str) -> bool:
    return bool(URL_PATTERN.search(text))


def atomize(text: str) -> AtomResult:
    """Atomizer (ADR-005): пробегает все категории, возвращает первое совпадение
    с наибольшим числом сматченных паттернов. Если ничего не найдено — category=none,
    confident=True (это тоже atomic-результат: уверенно ничего подозрительного).

    Корроборация (ADR-007): requires_url (phishing_link) — тревожная фраза без URL
    это обрывок, не скам; context_re (слабый OTP "глагол+код") — "дай/скажи код" без
    OTP-контекста (смс/банк/доставка/срочно…) это обычная переписка про код."""
    best = None
    url_present = has_url(text)
    for category, reason_code, ru_pats, kz_pats, requires_url, context_re in RULES:
        if requires_url and not url_present:
            continue
        if context_re is not None and not context_re.search(text):
            continue
        ru_hits = [p.pattern for p in ru_pats if p.search(text)]
        kz_hits = [p.pattern for p in kz_pats if p.search(text)]
        if ru_hits or kz_hits:
            score = len(ru_hits) + len(kz_hits)
            if best is None or score > best[0]:
                best = (score, AtomResult(category, reason_code, ru_hits, kz_hits, confident=True))
    if best:
        return best[1]
    return AtomResult("none", "no_signal", confident=True)


if __name__ == "__main__":
    tests = [
        # (текст, ожидаемая_category, комментарий)
        ("Ваш код подтверждения 4821, никому не сообщайте", "otp_shared_request", "RU OTP явный"),
        ("код скажи доставка каспи", "otp_shared_request", "RU OTP 'код скажи' + контекст"),
        ("Продиктуйте смс-код который пришёл", "otp_shared_request", "RU OTP 'продиктуйте смс-код'"),
        ("Растау коды: 8213, ешкімге айтпаңыз", "otp_shared_request", "KZ OTP явный"),
        ("Установите AnyDesk чтобы мы помогли с доступом", "remote_access_install", "RU remote явный"),
        ("Қашықтан басқару бағдарламасын орнатыңыз", "remote_access_install", "KZ remote явный"),
        ("Не кладите трубку, переведите деньги на безопасный счёт", "urgency_pressure", "RU urgency"),
        ("Телефонды тастамаңыз, ақшаны қауіпсіз шотқа аударыңыз", "urgency_pressure", "KZ urgency"),
        ("Поздравляем, вы выиграли миллион тенге!", "prize_lottery_scam", "RU приз"),
        ("Сіз ұтып алдыңыз! Жүлдені алу үшін хабарласыңыз", "prize_lottery_scam", "KZ приз"),
        ("Пассивный доход без риска, удвойте вложения за неделю", "financial_pyramid_signal", "RU пирамида"),
        ("Ақшаңызды екі есе көбейтіңіз, тәуекелсіз табыс", "financial_pyramid_signal", "KZ пирамида"),
        ("Здравствуйте, это служба безопасности Halyk, ваша карта заблокирована", "bank_impersonation", "RU банк-имперсонация"),
        ("Сәлеметсіз бе, Kaspi қауіпсіздік қызметі, картаңыз бұғатталды", "bank_impersonation", "KZ банк-имперсонация"),
        ("Перейдите по ссылке http://kaspi-secure.xyz и подтвердите данные карты", "phishing_link", "RU фишинг-ссылка"),
        ("Сілтеме арқылы өтіңіз: kaspi-bonus.top деректерді растаңыз", "phishing_link", "KZ фишинг-ссылка"),
        ("Я так полюбил тебя, но застрял на границе, переведи денег на билет", "romance_scam", "RU романтический скам"),
        ("Сені сүйіп қалдым, шекарада қалып қойдым, ақша аударшы", "romance_scam", "KZ романтический скам"),
    ]

    fp_tests = [
        # (текст, комментарий) — НЕ должны триггерить ничего опасного
        ("Привет, как дела? Встретимся вечером?", "обычная переписка"),
        ("Ваш заказ из Kaspi доставлен, спасибо за покупку", "легитимное уведомление"),
        ("Сабақ кестесі өзгерді, ертең 9-да", "KZ обычное сообщение (расписание)"),
        ("Не забудь перевести презентацию на английский", "RU 'перевод' = перевод текста, не денег"),
        ("Служба безопасности предприятия проводит инструктаж в пятницу", "RU 'служба безопасности' без банка"),
        ("Посмотри какой сайт классный: example.kz, там рецепты", "RU URL без тревожного контекста"),
        ("Я тебя люблю, увидимся вечером после работы", "RU романтика без денег"),
        ("скажи код", "RU 'скажи код' без скам-контекста"),
        ("дай код доступа к серверу", "RU дев: 'дай код доступа'"),
        ("код сообщения не дошёл", "RU 'код сообщения' без контекста"),
    ]

    print("=== ДОЛЖНЫ сработать ===")
    all_ok = True
    for text, expected, note in tests:
        r = atomize(text)
        ok = r.category == expected
        all_ok &= ok
        print(f"  [{'OK' if ok else 'FAIL'}] {note}: got={r.category} matched={r.matched_ru + r.matched_kz}")

    print("\n=== НЕ должны сработать (контроль ложных срабатываний) ===")
    for text, note in fp_tests:
        r = atomize(text)
        ok = r.category == "none"
        all_ok &= ok
        flag = "OK" if ok else "FALSE POSITIVE"
        print(f"  [{flag}] {note}: got={r.category} matched={r.matched_ru + r.matched_kz}  «{text}»")

    print(f"\n{'ВСЕ ПРОШЛИ' if all_ok else 'ЕСТЬ ПРОБЛЕМЫ — см. FAIL/FALSE POSITIVE выше'}")
