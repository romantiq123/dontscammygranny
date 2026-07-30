# `:app` — Android-модуль

Специфика Android-слоя. **Статус компонентов и планы — в корневом
[HANDOFF.md](../HANDOFF.md)**, разбор багов и находок — в
[ENGINEERING_NOTES.md](../ENGINEERING_NOTES.md), архитектурные решения — в
[decisions.md](../decisions.md). Здесь только то, что касается именно этого модуля.

Вся детект-логика живёт в `:core` (чистый Kotlin/JVM, тестируется без Android SDK).
`:app` — это каналы захвата, системные разрешения, нативный мост и вывод.

## Что в модуле

| Пакет / файл | Роль |
|---|---|
| `ShieldApp.kt` | Application: каналы уведомлений, старт `ContactsLoader`/`CallStateWatcher`, прогрев LLM |
| `notification/ShieldNotificationListenerService` | ADR-001/007/012 — захват текста; плюс детект **VoIP-звонков мессенджеров**, отсев системных уведомлений и дедуп |
| `callscreening/ShieldCallScreeningService` | ADR-011 — скрининг сотовых звонков (`ROLE_CALL_SCREENING`) |
| `pipeline/ShieldPlanner` | Единая точка входа в Planner: синглтон `ShieldEngine` + сериализованный dispatcher для LLM |
| `pipeline/ContactsLoader` | Читает `ContactsContract` → «звонок из контактов = тишина» |
| `pipeline/CallStateWatcher` | Точный конец сотового звонка (`TelephonyCallback`/`PhoneStateListener`) |
| `pipeline/BankAppMonitor` + `WatchedApps` | Детект «наблюдаемое приложение открыто во время рискованного звонка» через `UsageStats` |
| `pipeline/PackageAddedReceiver` | Установка remote-access приложений (AnyDesk/TeamViewer/…) |
| `pipeline/ShieldPipelineForegroundService` | Скелет shortService (ADR-008). **Сознательно не подключён** — обоснование в KDoc и ENGINEERING_NOTES §4.6 |
| `llm/LlamaCppExecutor` | Инференс через JNI, GBNF-ограниченный вывод, скачивание модели |
| `llm/CachingLlmExecutor` + `VerdictCache` | SQLite-кэш вердиктов по точному нормализованному тексту |
| `llm/ModelPrefs` | Согласие на скачивание + режим «только Wi-Fi» (ADR-006) |
| `llm/LlamaBridge` + `cpp/llama_bridge.cpp` | JNI-мост к llama.cpp (ADR-002/004) |
| `alert/AlertPresenter` | ADR-013 — heads-up уведомления + вибро (TTS и FSI ещё нет) |
| `ui/MainActivity`, `ui/LogActivity` | Онбординг (статусы доступов) и экран лога |
| `log/FileLog` | Лог в файл, читается прямо в приложении — диагностика без adb |

## Разрешения и почему они нужны

Все ключевые доступы — **спец-разрешения, которые нельзя запросить обычным диалогом**;
онбординг ведёт пользователя в нужный системный экран.

| Разрешение / роль | Зачем | Как выдаётся |
|---|---|---|
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | основной канал захвата текста | системный экран |
| `ROLE_CALL_SCREENING` | скрининг входящих звонков | `RoleManager` |
| `PACKAGE_USAGE_STATS` | какое приложение на переднем плане во время звонка | системный экран |
| `READ_CONTACTS` | «знакомый номер = тишина» | runtime-диалог |
| `READ_PHONE_STATE` | точный конец звонка (иначе — таймаут окна) | runtime-диалог |
| `POST_NOTIFICATIONS` | показать предупреждение (API 33+) | runtime-диалог |
| `INTERNET` | **единственное** сетевое обращение — разовое скачивание модели (после явного согласия) | — |
| `ACCESS_NETWORK_STATE` | проверка «сеть — Wi-Fi?» для режима скачивания «только по Wi-Fi» | — |
| `VIBRATE` | вибро-предупреждение (ADR-013, особенно в канале звонка — без звука) | — |
| `FOREGROUND_SERVICE` | объявлено под shortService (ADR-008); сам сервис пока сознательно не подключён | — |
| `USE_FULL_SCREEN_INTENT` | объявлено под полноэкранный алерт (ADR-013); в коде ещё **не используется** | — |

`SYSTEM_ALERT_WINDOW` не используется намеренно (ADR-013: overlay — сигнатурный признак
banking-малвари, лишний риск при ревью Play).

## Нативная часть и модель

- llama.cpp подключён **сабмодулем** в `src/main/cpp/llama.cpp` (коммит `76f46ad2`,
  тег `gguf-v0.19.0-1029`). CMake собирает только `libllama`, без cli/server/examples/tests.
- Собирается только `arm64-v8a`; `armeabi-v7a` отложен (ADR-002).
- **Модель не вшита в APK.** Скачивается на устройство после явного согласия
  пользователя (`ModelPrefs`), по умолчанию только по Wi-Fi. Без модели приложение
  работает на regex-слое — `infer()` просто возвращает `null`.
- `src/main/assets/verdict.gbnf` — **копия** корневой грамматики; при правке схемы
  синхронизировать обе (и `verdict_schema.json`).

> ⚠️ После правок в `cpp/` нужен **чистый** пересбор нативки, иначе ninja переиспользует
> старые объектники — см. [ENGINEERING_NOTES §5.1](../ENGINEERING_NOTES.md).
>
> ⚠️ llama.cpp не держит стабильный C API между релизами. Если после обновления
> сабмодуля сборка падает на несуществующих символах — это дрейф API, сверяйся с
> `include/llama.h` этого коммита.

## Сборка

Требования и команды — в корневом [HANDOFF.md](../HANDOFF.md), раздел «Как собрать и
прогнать тесты». Коротко:

```bash
./gradlew :app:assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```
