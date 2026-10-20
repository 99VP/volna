cmake_minimum_required(VERSION 3.22.1)

project(volna_byedpi)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(volna_byedpi SHARED ${BYE_DPI_SRC} native-lib.c utils.c)
target_include_directories(volna_byedpi PRIVATE byedpi)

target_compile_options(volna_byedpi PRIVATE -std=c99 -O2 -D_XOPEN_SOURCE=500)
target_compile_definitions(volna_byedpi PRIVATE ANDROID_APP)

target_link_libraries(volna_byedpi PRIVATE android log)
