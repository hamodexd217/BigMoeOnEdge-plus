cmake_minimum_required(VERSION 3.22.1)
project("onedge-prebuilt-placeholder" LANGUAGES C CXX)

# For a Hexagon/NPU APK the real JNI library is staged in app/src/main/jniLibs/arm64-v8a
# by scripts/build-hexagon-onedge-engine.sh. Gradle still asks CMake for a target, so give it
# a tiny static target that produces no conflicting shared library. The actual libonedge-engine.so
# is intentionally NOT rebuilt here.
add_library(onedge-native-prebuilt STATIC noop.cpp)
