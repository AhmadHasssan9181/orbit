cmake_minimum_required(VERSION 3.18)
project(OrbitAudioJNI VERSION 1.0 DESCRIPTION "Custom WebRTC ADM")

# Upgrade to C++20 to satisfy WebRTC's modern header requirements
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

# -----------------------------------------------------------------------------
# HARDCODED VCPKG PATHS (UNIFIED DIRECTORY)
# -----------------------------------------------------------------------------
# Use the "installed" directory so it finds WebRTC AND Abseil (absl) dependencies
set(VCPKG_INSTALLED_DIR "D:/vcpkg/installed/x64-windows")

set(WEBRTC_INCLUDE_DIR "${VCPKG_INSTALLED_DIR}/include")
set(WEBRTC_LIB_DIR "${VCPKG_INSTALLED_DIR}/lib")

add_library(${PROJECT_NAME} SHARED
    src/OrbitAudioDeviceModule.cc
    src/OrbitJNI.cc
)

target_include_directories(${PROJECT_NAME} PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${WEBRTC_INCLUDE_DIR}
    ${WEBRTC_INCLUDE_DIR}/webrtc
    src
)

target_link_directories(${PROJECT_NAME} PRIVATE
    ${WEBRTC_LIB_DIR}
)

target_link_libraries(${PROJECT_NAME} PRIVATE
    webrtc.lib
    Mmdevapi.lib
    Ole32.lib
)
