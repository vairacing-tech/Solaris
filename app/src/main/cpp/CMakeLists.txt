cmake_minimum_required(VERSION 3.22.1)
project(gamestream_native)

add_library(gamestream_native SHARED native-lib.cpp)

find_library(log-lib log)
target_link_libraries(gamestream_native ${log-lib})
