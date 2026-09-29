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

set(LIVEKIT_CPP_SDK_VERSION "0.4.1" CACHE STRING "LiveKit CPP SDK version")

# SHA-256 of livekit-sdk-windows-x64-0.4.1.zip. Override with
# -DLIVEKIT_CPP_SDK_SHA256=... when bumping the version.
#
# FORCE matters: a plain `set(... CACHE ...)` is a no-op once the entry exists,
# and build/jni is no longer wiped between builds, so without FORCE a version or
# digest bump in this file would be silently ignored and the build would keep
# relinking the previously-cached SDK -- making the digest check itself inert.
set(LIVEKIT_CPP_SDK_SHA256 "adfe65917efd58492489d1ad08d47e4783bf556e8aa74a272ea3442fe5154651"
    CACHE STRING "LiveKit C++ SDK archive SHA-256" FORCE)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(LiveKitSDK)
orbit_livekit_sdk_setup(
    VERSION "${LIVEKIT_CPP_SDK_VERSION}"
    SDK_DIR "${CMAKE_BINARY_DIR}/_deps/livekit-sdk"
    # Digest of the release archive for LIVEKIT_CPP_SDK_VERSION. The SDK is linked
    # into the shipped app, so the download is verified before it is used.
    SHA256 "${LIVEKIT_CPP_SDK_SHA256}"
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

if(WIN32)
    target_link_libraries(orbit_livekit_audio PRIVATE mmdevapi.lib)
endif()

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
