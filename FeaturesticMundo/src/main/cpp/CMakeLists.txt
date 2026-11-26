cmake_minimum_required(VERSION 3.22.1)
project(MundoEngine LANGUAGES CXX)

add_library(MundoEngine SHARED
    MundoCore.cpp
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(MundoEngine
    ${log-lib}
    ${dl-lib}
)
