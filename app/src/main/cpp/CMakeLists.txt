# ADR-002: llama.cpp через NDK, CPU-only. Не вендорим сборку llama.cpp сами —
# ожидаем исходники как git submodule в app/src/main/cpp/llama.cpp (см.
# app/README.md, "Как включить LLM-Executor"). Разработчик добавляет submodule
# на своей машине (здесь, при написании этого файла, git-репозиторий вообще не
# инициализирован — см. HANDOFF.md).

cmake_minimum_required(VERSION 3.22.1)
project(llama_bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if(NOT EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp не найден в ${LLAMA_CPP_DIR}. Добавь его как git submodule "
        "(см. app/README.md, раздел \"Как включить LLM-Executor\") перед сборкой :app.")
endif()

# Нужен только libllama (движок инференса), не llama-cli/сервер/тесты — держим
# сборку и APK меньше (ADR-002 Consequences: размер APK влияет на охват аудитории).
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE) # NDK toolchain не даёт libomp по умолчанию

# КРИТИЧНО ДЛЯ ПРОИЗВОДИТЕЛЬНОСТИ: для debug-варианта APK Gradle/AGP передаёт
# CMAKE_BUILD_TYPE=Debug, и ggml/llama компилируются БЕЗ оптимизации (NDK-дефолт
# CMAKE_C_FLAGS_DEBUG = "-g", т.е. фактически -O0). На -O0 инференс идёт в ~100x
# медленнее (llama_decode промпта из 137 токенов: ~113с вместо <1с), из-за чего
# систему успевает прибить процесс по таймауту, и LLM «зависает».
#
# ВАЖНО (проверено по факту 2026-07-22): одного `set(CMAKE_C_FLAGS_DEBUG "-O3"
# ... FORCE)` НЕДОСТАТОЧНО — ninja всё равно компилировал ggml-cpu.c с "-g" без
# -O3 (переменная попадала в CMakeCache, но не в команды компиляции таргетов
# ggml). Поэтому форсим -O3 напрямую через add_compile_options: это свойство
# COMPILE_OPTIONS директории, которое НАСЛЕДУЕТСЯ всеми таргетами, добавленными
# ниже (ggml/llama через add_subdirectory), и гарантированно попадает в команду
# компиляции. -O3 идёт последним => побеждает дефолтный -O0. NDEBUG выключает
# GGML_ASSERT (лишние проверки в горячем цикле). Форсим НЕЗАВИСИМО от типа
# Gradle-сборки; в Release ggml и сам ставит -O3 (двойной -O3 безвреден).
add_compile_options(-O3)
add_compile_definitions(NDEBUG)

# Оставляем и переопределение флагов Debug-конфигурации — на случай таргетов,
# читающих CMAKE_<LANG>_FLAGS_DEBUG напрямую (дублирующая подстраховка к строкам выше).
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} llama_cpp_build)

add_library(llama_bridge SHARED llama_bridge.cpp)

target_link_libraries(llama_bridge
    llama
    android
    log
)
