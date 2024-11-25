# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

#Set the project name
project(AprilTagDetectionLibrary C)

set(CMAKE_SOURCE "src/main")

#Include the apriltag directory for header files
include_directories(${CMAKE_SOURCE}/apriltag)

#Collect all '.c' files in the apriltag directory
file(GLOB_RECURSE APRILTAG_SRC "${CMAKE_SOURCE}/apriltag/*.c")

message(STATUS   "SOURCE DIR:   ${CMAKE_SOURCE}")
message(STATUS  "APRILTAG_SRC: ${APRILTAG_SRC}")

#Add apriltag_jni.c to the source files
set(SOURCES ${APRILTAG_SRC})

message(STATUS  "SOURCES: ${SOURCES}")

set(CMAKE_C_STANDARD 99)

#set(CMAKE_C_COMPILER /usr/bin/clang)

set(LIBRARY_NAME  "apriltag")

#Define the native library
add_library(
        ${LIBRARY_NAME}  #name of the library
        SHARED                 #Shared library
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        ${SOURCES})

find_library(
        log-lib  #Variable to store the log library
        log      #Android NDK log library
)

find_library(
        jnigraphics-lib
        jnigraphics
)

target_link_libraries(
        ${LIBRARY_NAME}
        ${log-lib}
        ${jnigraphics-lib}
)