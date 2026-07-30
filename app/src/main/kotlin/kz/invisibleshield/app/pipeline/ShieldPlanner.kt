package kz.invisibleshield.app.pipeline

import kz.invisibleshield.app.ShieldApp
import kz.invisibleshield.app.llm.CachingLlmExecutor
import kz.invisibleshield.app.llm.LlamaCppExecutor
import kz.invisibleshield.app.llm.VerdictCache
import kz.invisibleshield.core.executor.LlmExecutor
import kz.invisibleshield.core.planner.ShieldEngine
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Единая точка входа в Planner (ADR-005) для всего приложения. NLS-события (SMS/
 * уведомления) и CallScreeningService-события (звонки) должны видеть ОДНО и то же
 * состояние "активный рискованный звонок" — поэтому ShieldEngine здесь синглтон,
 * а не создаётся заново на каждое событие.
 *
 * ВАЖНО про потоки: engine трогается из РАЗНЫХ потоков — onSms с [dispatcher],
 * onCallStarted с Binder-потока CallScreeningService, onAppInstalled с main-потока
 * BroadcastReceiver. [dispatcher] сериализует НЕ их, а только LLM-доступ к
 * llama_context (ADR-002/004: "один llama_context, доступ сериализован"). За
 * гонку самого CallState отвечает внутренняя синхронизация ShieldEngine (все его
 * публичные методы @Synchronized) — она не зависит от того, с какого потока звать.
 *
 * dispatcher: один выделенный поток, НЕ конкурентный пул. Через него идут вызовы
 * LlmExecutor'а (ADR-002/004) — способ enforce'ить сериализованный доступ к
 * llama_context.
 *
 * TODO(ADR-005 open item): очередь/lock при параллельных LLM-событиях — сейчас
 * сериализация LLM обеспечивается только тем, что dispatcher однопоточный; если два
 * события придут одновременно, второе просто ждёт своей очереди в диспетчере.
 * Явного контракта поведения при переполнении очереди (таймаут? drop?) пока нет.
 *
 * contacts: движок создаётся с пустым списком, а реальные номера подгружаются
 * асинхронно ContactsLoader'ом (ShieldApp.onCreate / после выдачи READ_CONTACTS
 * в MainActivity) через engine.updateContacts() — см. ContactsLoader.
 */
object ShieldPlanner {

    val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "shield-planner") }.asCoroutineDispatcher()

    val engine: ShieldEngine = ShieldEngine(contacts = emptyList())

    // Реальный исполнитель (native llama.cpp). Держим отдельной ссылкой, чтобы UI
    // мог управлять скачиванием модели (startDownload), минуя кэш-обёртку.
    private val llama: LlamaCppExecutor by lazy { LlamaCppExecutor(ShieldApp.context) }

    // Публичный исполнитель для пайплайна — с кэшем вердиктов (VerdictCache): повтор
    // того же сообщения берётся из локальной БД, дорогой инференс не гоняется.
    // lazy: модель может вообще отсутствовать на устройстве, грузим лениво.
    val llmExecutor: LlmExecutor by lazy {
        CachingLlmExecutor(llama, VerdictCache(ShieldApp.context))
    }

    /** Явное согласие пользователя на скачивание модели (кнопка «Скачать» в UI). */
    fun startModelDownload() = llama.startDownload()
}
