package kz.invisibleshield.core.executor

/**
 * Промпт для LLM-Executor'а (ADR-002/003/004). Category от Atomizer передаётся
 * как подсказка: LLM либо подтверждает её, либо переклассифицирует по полному
 * тексту (verdict_schema.json, описание поля category). Вывод ограничен GBNF-
 * грамматикой (verdict.gbnf) на стороне инференса — этот промпт лишь просит
 * модель следовать формату, саму валидность гарантирует grammar-constrained
 * decoding, а не формулировка (ADR-004 Why).
 */
object LlmPromptBuilder {

    fun build(text: String, atomCategory: String, callContext: String = ""): String = buildString {
        // ChatML-формат Qwen (<|im_start|>role … <|im_end|>) — instruct-модель обучена
        // именно на нём; подача СЫРОГО текста держит её вне режима «следуй инструкции»
        // (на устройстве 2026-07-22 сырой промпт давал побайтно одинаковый none на
        // разных текстах). Токенизатор моста парсит спец-токены (parse_special=true),
        // поэтому эти маркеры — реальные управляющие токены, а закрывающий <|im_end|>
        // ассистента = EOG (id 151645 в логе) -> генерация встаёт сама.
        // Few-shot даём НАСТОЯЩИМИ парами user/assistant. Вывод ассистента жёстко
        // ограничен GBNF (ADR-004) — промпт влияет только на ВЫБОР значений.
        append("<|im_start|>system\n")
        append("Ты классифицируешь сообщения на мошенничество для пожилых (RU/KZ). ")
        append("Отвечаешь ТОЛЬКО одним JSON: level (none|info|warn|danger), confidence, ")
        append("category, reason_code.\n")
        append("ПРАВИЛО: regex уже нашёл подозрительную категорию — по умолчанию ")
        append("ПОДТВЕРДИ её (warn/danger); none только если текст явно безобиден.\n")
        append("Признаки скама: код из СМС; удалённый доступ (AnyDesk); торопят/пугают ")
        append("блокировкой; подозрительная ссылка; «служба безопасности банка»; выигрыш.")
        append("<|im_end|>\n")
        // Few-shot: две настоящие пары user->assistant (контраст скам/безобидное).
        // Тексты примеров в « », чтобы тройные кавычки остались уникальным
        // разделителем ТОЛЬКО вокруг реального сообщения.
        append("<|im_start|>user\nТекст: «Назовите код из СМС, иначе заблокируем»<|im_end|>\n")
        append("<|im_start|>assistant\n{\"level\":\"danger\",\"confidence\":\"0.9\",\"category\":\"otp_shared_request\",\"reason_code\":\"asks_otp_code\"}<|im_end|>\n")
        append("<|im_start|>user\nТекст: «Привет, как дела?»<|im_end|>\n")
        append("<|im_start|>assistant\n{\"level\":\"none\",\"confidence\":\"0.0\",\"category\":\"none\",\"reason_code\":\"no_signal\"}<|im_end|>\n")
        // Реальный запрос.
        append("<|im_start|>user\n")
        append("Категория от regex: ").append(atomCategory).append('\n')
        if (callContext.isNotBlank()) {
            append("Контекст звонка: ").append(callContext).append('\n')
        }
        append("Текст: \"\"\"").append(text.replace("\"\"\"", "'''")).append("\"\"\"")
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }
}
