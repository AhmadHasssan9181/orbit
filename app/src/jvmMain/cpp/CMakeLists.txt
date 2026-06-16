cmake_minimum_required(VERSION 3.20)
project(orbit_livekit_bridges)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add JNI support
find_package(JNI REQUIRED)

add_library(orbit_livekit_video SHARED
    orbit_livekit_video.cpp
)

target_include_directories(orbit_livekit_video PRIVATE
    ${JNI_INCLUDE_DIRS}
    include
)

target_link_libraries(orbit_livekit_video
    ${JNI_LIBRARIES}
)

set(LIVEKIT_CPP_SDK_VERSION "0.4.1" CACHE STRING "LiveKit C++ SDK version")

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(LiveKitSDK)
orbit_livekit_sdk_setup(
    VERSION "${LIVEKIT_CPP_SDK_VERSION}"
    SDK_DIR "${CMAKE_BINARY_DIR}/_deps/livekit-sdk"
)

find_package(LiveKit CONFIG REQUIRED)

add_library(orbit_livekit_audio SHARED
    orbit_livekit_audio.cpp
)

target_include_directories(orbit_livekit_audio PRIVATE
    ${JNI_INCLUDE_DIRS}
    include
)

target_link_libraries(orbit_livekit_audio PRIVATE
    ${JNI_LIBRARIES}
    LiveKit::livekit
)

add_executable(orbit_livekit_tone_publisher
    livekit_tone_publisher.cpp
)

target_link_libraries(orbit_livekit_tone_publisher PRIVATE
    LiveKit::livekit
)

if(WIN32 AND DEFINED ORBIT_LIVEKIT_SDK_ROOT)
    add_custom_command(TARGET orbit_livekit_tone_publisher POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${ORBIT_LIVEKIT_SDK_ROOT}/bin"
            "$<TARGET_FILE_DIR:orbit_livekit_tone_publisher>"
    )
endif()
