// Placeholder translation unit for the `orbit_livekit_video` DLL.
//
// WHY this file is still here: the intended separate JNI bridge for the video
// processor (local camera/screen capture pipelines, frame conversion and
// rotation) is UNRESOLVED. Video publishing today runs entirely inside
// orbit_livekit_audio.cpp, which owns the session mutex, the epoch checks and
// the direct-ByteBuffer frame push path; nothing is registered on the JVM side
// against this library, so it exports no symbols and is loaded for nothing.
//
// The CMake target is intentionally left in place so the build wiring does not
// have to change when the video processor is implemented. Do not treat the
// resulting DLL as a working feature: it is a build artifact with no code
// behind it yet.
#include <jni.h>
