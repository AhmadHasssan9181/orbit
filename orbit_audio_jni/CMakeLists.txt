cmake_minimum_required(VERSION 3.18)
project(OrbitAudioJNI VERSION 1.0 DESCRIPTION "Custom WebRTC ADM & Video Processor")

# Upgrade to C++20 to satisfy WebRTC's modern header requirements
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)
find_package(OpenCV REQUIRED)

# -----------------------------------------------------------------------------
# HARDCODED VCPKG PATHS (UNIFIED DIRECTORY)
# -----------------------------------------------------------------------------
set(VCPKG_INSTALLED_DIR "D:/vcpkg/installed/x64-windows")

set(WEBRTC_INCLUDE_DIR "${VCPKG_INSTALLED_DIR}/include")
set(WEBRTC_LIB_DIR "${VCPKG_INSTALLED_DIR}/lib")

# Glob all abseil libraries and other dependencies
file(GLOB ABSL_LIBS "${WEBRTC_LIB_DIR}/absl_*.lib")
list(APPEND ABSL_LIBS "${WEBRTC_LIB_DIR}/abseil_dll.lib")

add_library(${PROJECT_NAME} SHARED
    src/OrbitAudioDeviceModule.cc
    src/OrbitVideoProcessor.cc
    src/OrbitJNI.cc
)

target_compile_definitions(${PROJECT_NAME} PRIVATE
    WEBRTC_WIN
    NOMINMAX
    _WINSOCKAPI_
)

target_include_directories(${PROJECT_NAME} PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${WEBRTC_INCLUDE_DIR}
    ${WEBRTC_INCLUDE_DIR}/webrtc
    ${OpenCV_INCLUDE_DIRS}
    src
)

target_link_directories(${PROJECT_NAME} PRIVATE
    ${WEBRTC_LIB_DIR}
)

target_link_libraries(${PROJECT_NAME} PRIVATE
    webrtc.lib
    libyuv.lib
    Winmm.lib
    Mmdevapi.lib
    Ole32.lib
    ${OpenCV_LIBS}
    ${ABSL_LIBS}
)
