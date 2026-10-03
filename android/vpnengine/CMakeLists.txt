# Обёртка ядра OpenVPN 3 для Android: разделяемая библиотека с Java-биндингом.
#
# Зачем этот файл. В upstream:
#  - ядро НЕ собирается отдельной библиотекой: `add_core_dependencies(${target})`
#    вливает его исходники прямо в вызывающую цель;
#  - SWIG настроен только на Python (`swig_add_library(... LANGUAGE python)`),
#    Java-биндинга нет вовсе.
# Значит свою цель заводить обязательно. Ровно так же поступает ics-openvpn:
# создаёт библиотеку, вызывает add_core_dependencies и генерирует Java-обёртку
# своим вызовом SWIG с `-DOPENVPN_PLATFORM_ANDROID`.
#
# Что получается на выходе: `libovpn3.so`, внутри — ядро 3.x и Java-классы
# `net.openvpn.ovpn3.*`, которые компилятор Java увидит как обычную библиотеку.
#
# Переменные окружения/кэша:
#   OPENVPN3_DIR — каталог склонированного upstream ядра
#   CORE_DIR внутри ядра подставляется самим include(findcoredeps)

cmake_minimum_required(VERSION 3.22)
project(ovpn3android CXX)

set(CMAKE_CXX_STANDARD 23)

# Ядро берём из отдельного чекаута, не копируя к себе.
if (NOT OPENVPN3_DIR)
    message(FATAL_ERROR "OPENVPN3_DIR не задан — укажите путь к ядру OpenVPN 3")
endif()

# findcoredeps.cmake ожидает каталог деп-как-родителя; задаём явно, чтобы не
# зависеть от раскладки.
set(DEP_DIR "${OPENVPN3_DIR}/../deps" CACHE PATH "Dependencies")

# --- зависимости ядра -------------------------------------------------------
# findcoredeps должен быть подключён ДО любого find_package(asio/lz4/fmt):
# он добавляет свой модульный путь (cmake/) и через add_core_dependencies
# цепляет зависимости к цели. Без него CMake ищет asio обычным способом и
# падает с «Findasio.cmake not provided» — это и была ошибка прогона.
list(APPEND CMAKE_MODULE_PATH "${OPENVPN3_DIR}/cmake")
include(${OPENVPN3_DIR}/cmake/findcoredeps.cmake)

# --- Java через SWIG ---------------------------------------------------------
# Для Java версию языка задаём здесь: в upstream настроен только Python.
find_package(SWIG 4.0 REQUIRED java)
include(${SWIG_USE_FILE})

set(CMAKE_SWIG_FLAGS
    "-c++"
    "-java"
    "-package" "net.openvpn.ovpn3"
    # Этот define и есть переключатель Android-режима ядра: по нему
    # платформенный слой выбирает реализацию tun-слоя.
    "-DOPENVPN_PLATFORM_ANDROID"
    "-I${OPENVPN3_DIR}/client"
    "-I${OPENVPN3_DIR}"
)

# --- цель библиотеки --------------------------------------------------------
# ovpncli.cpp/.i — точка входа ClientAPI ядра; всё остальное подтянет
# add_core_dependencies ниже.
set(SWIG_SOURCES
    ${OPENVPN3_DIR}/client/ovpncli.cpp
    ${OPENVPN3_DIR}/client/ovpncli.i
)

add_library(ovpn3 SHARED ${SWIG_SOURCES})

set_target_properties(ovpn3 PROPERTIES
    # Имя без префикса: так .so подхватывается как обычная JNI-библиотека.
    PREFIX ""
    OUTPUT_NAME "ovpn3"
)

# На Android pthread — часть libc, отдельной `libpthread.so` в NDK нет.
# Ядро в своей Linux-ветке безусловно делает
# `target_link_libraries(${target} pthread)`, и линковка падает с
# «ld.lld: error: unable to find library -lpthread».
#
# Подменяем имя пустым INTERFACE-таргетом: CMake перестаёт искать системную
# библиотеку, а символы pthread всё равно приходят из libc. Это должно стоять
# ДО add_core_dependencies, иначе имя уже уйдёт в линковку как системное.
add_library(pthread INTERFACE)

# Добавляет все исходники ядра, зависимости (asio, lz4, fmt, openssl/mbedtls)
# и нужные определяющие макросы к нашей цели.
add_core_dependencies(ovpn3)

target_compile_options(ovpn3 PRIVATE
    -Wno-deprecated-declarations
    -Wno-sometimes-uninitialized
    # SWIG генерирует код, который валит эти предупреждения; в upstream
    # отключены ровно эти же.
    -Wno-class-memaccess
    -Wno-unused-function
    -Wno-conversion
)

target_include_directories(ovpn3 PRIVATE
    ${OPENVPN3_DIR}
    ${OPENVPN3_DIR}/client
)
