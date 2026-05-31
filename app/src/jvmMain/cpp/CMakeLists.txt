cmake_minimum_required(VERSION 3.10)
project(orbit_livekit_video)

set(CMAKE_CXX_STANDARD 17)

# Add JNI support
find_package(JNI REQUIRED)

# Include directories
include_directories(${JNI_INCLUDE_DIRS})
include_directories(include)

# The shared library target
add_library(orbit_livekit_video SHARED
    orbit_livekit_video.cpp
)

# Link against standard libraries
target_link_libraries(orbit_livekit_video
    ${JNI_LIBRARIES}
)
