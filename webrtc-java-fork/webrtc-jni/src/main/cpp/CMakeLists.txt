cmake_minimum_required(VERSION 3.13)

if(NOT WEBRTC_TOOLCHAIN_FILE STREQUAL "")
    message(STATUS "Toolchain file: ${WEBRTC_TOOLCHAIN_FILE}")
    set(CMAKE_TOOLCHAIN_FILE "${WEBRTC_TOOLCHAIN_FILE}")
endif()

project(webrtc-java-orbit)

if(UNIX AND NOT APPLE)
    # Apply the sysroot configuration for the actual build
    if(DEFERRED_SYSROOT)
        # Configure sysroot for all subsequent operations
        set(CMAKE_SYSROOT ${DEFERRED_SYSROOT})
        set(CMAKE_FIND_ROOT_PATH ${DEFERRED_SYSROOT})

        # Set pkg-config environment
        set(ENV{PKG_CONFIG_SYSROOT_DIR} "${DEFERRED_SYSROOT}")
        set(ENV{PKG_CONFIG_PATH} "${DEFERRED_SYSROOT}/usr/lib/pkgconfig:${DEFERRED_SYSROOT}/usr/share/pkgconfig")

        # Restrict library and header searches to sysroot only
        set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
        set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
        set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
    endif()

    set(LINUX TRUE)
endif()

if(APPLE)
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")
    set(SOURCE_TARGET macos)
elseif(LINUX)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")
    set(SOURCE_TARGET linux)
elseif(WIN32)
    set(SOURCE_TARGET windows)
endif()

if(MSVC)
    add_compile_options(/W3 /wd4005 /wd4068 /wd4101 /wd4267 /wd4996)
endif()

if (CMAKE_CXX_COMPILER_ID STREQUAL Clang AND CMAKE_CXX_COMPILER_FRONTEND_VARIANT STREQUAL "MSVC")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} /showFilenames")
endif()

# Use vcpkg WebRTC instead of downloading it
set(AOM_DIR "D:/vcpkg/installed/x64-windows/share/aom")
list(APPEND CMAKE_PREFIX_PATH "D:/vcpkg/installed/x64-windows")
find_package(unofficial-webrtc CONFIG REQUIRED)
add_subdirectory(dependencies/jni-voithos)

file(GLOB SOURCES_ROOT "src/*.cpp")
file(GLOB SOURCES_API "src/api/*.cpp")
file(GLOB SOURCES_API_AUDIO "src/api/audio/*.cpp")
file(GLOB SOURCES_MEDIA "src/media/*.cpp")
file(GLOB SOURCES_MEDIA_AUDIO "src/media/audio/*.cpp")
file(GLOB SOURCES_MEDIA_AUDIO_OS "src/media/audio/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO "src/media/video/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_DESKTOP "src/media/video/desktop/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_DESKTOP_OS "src/media/video/desktop/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_OS "src/media/video/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_PLATFORM "src/platform/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_RTC "src/rtc/*.cpp")

# RNNoise Integration
file(GLOB SOURCES_RNNOISE "rnnoise/src/*.c")
list(REMOVE_ITEM SOURCES_RNNOISE 
    "${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src/dump_features.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src/dump_rnnoise_tables.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src/write_weights.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src/rnnoise_data_little.c"
)

list(APPEND SOURCES
    ${SOURCES_ROOT}
    ${SOURCES_API}
    ${SOURCES_API_AUDIO}
    ${SOURCES_MEDIA}
    ${SOURCES_MEDIA_AUDIO}
    ${SOURCES_MEDIA_AUDIO_OS}
    ${SOURCES_MEDIA_VIDEO}
    ${SOURCES_MEDIA_VIDEO_DESKTOP}
    ${SOURCES_MEDIA_VIDEO_DESKTOP_OS}
    ${SOURCES_MEDIA_VIDEO_OS}
    ${SOURCES_PLATFORM}
    ${SOURCES_RTC}
    ${SOURCES_RNNOISE}
)

add_library(${PROJECT_NAME} SHARED ${SOURCES})

if (CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
    target_compile_options(${PROJECT_NAME} PRIVATE -Wno-nullability-completeness)
endif()

target_include_directories(${PROJECT_NAME}
    PRIVATE
        include
        include/api
        include/media
        include/rtc
        rnnoise/include
        rnnoise/src
        "D:/vcpkg/installed/x64-windows/include/webrtc"
)

set_target_properties(${PROJECT_NAME} PROPERTIES
    OUTPUT_NAME "${PROJECT_NAME}-${OUTPUT_NAME_SUFFIX}"
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

target_link_libraries(${PROJECT_NAME} PRIVATE jni-voithos)
target_link_libraries(${PROJECT_NAME} PRIVATE unofficial::webrtc::webrtc)

if(APPLE)
    set_source_files_properties(${SOURCES} PROPERTIES COMPILE_FLAGS "-x objective-c++")
    target_link_options(${PROJECT_NAME} PRIVATE "-ObjC")
    target_link_libraries(${PROJECT_NAME} PRIVATE "-framework Foundation" "-framework AVFoundation" "-framework CoreMedia" "-framework CoreAudio" "-framework IOKit" "-framework CoreVideo" "-framework VideoToolbox" "-framework QuartzCore")
elseif(LINUX)
    if(NOT TARGET_CPU MATCHES "^arm")
        set(CXX_LIBS "-static-libgcc -stdlib=libc++ -lc++ -lc++abi")
    else()
        set(CXX_LIBS "-static-libgcc")
    endif()

    target_link_libraries(${PROJECT_NAME} PRIVATE ${CXX_LIBS} pulse udev)
elseif(WIN32)
    target_link_libraries(${PROJECT_NAME} PRIVATE dwmapi.lib mf.lib mfreadwrite.lib mfplat.lib mfuuid.lib shcore.lib strmiids.lib d3d11.lib dxgi.lib)
endif()

install(TARGETS ${PROJECT_NAME}
    RUNTIME DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
    LIBRARY DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
)

list(APPEND CMAKE_PREFIX_PATH "D:/vcpkg/installed/x64-windows")


add_definitions(-DUNICODE -D_UNICODE)

