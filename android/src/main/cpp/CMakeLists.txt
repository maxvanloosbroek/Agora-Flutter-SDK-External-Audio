# The Flutter tooling requires that developers have CMake 3.10 or later
# installed. You should not increase this version, as doing so will cause
# the plugin to fail to compile for some customers of the plugin.
cmake_minimum_required(VERSION 3.13.5)

set(LIBRARY_NAME iris_rendering_android)

set(LIBRARY_VERSION "0.1.0")

project(${LIBRARY_NAME} VERSION ${LIBRARY_VERSION})

set(SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/vm_util.h"
    "${CMAKE_CURRENT_SOURCE_DIR}/iris_rtc_rendering_android.cc"
    "${CMAKE_CURRENT_SOURCE_DIR}/external_audio_render.cc"
    "${CMAKE_CURRENT_SOURCE_DIR}/aec_processor.cc"
     )

add_library(${LIBRARY_NAME} SHARED
  ${SOURCES}
)

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(${LIBRARY_NAME}
        PRIVATE
        ${log-lib}
        ${android-lib}
        GLESv2
        EGL
        )

# Support Android 15 16k page size
if (${ANDROID_ABI} STREQUAL "arm64-v8a" OR ${ANDROID_ABI} STREQUAL "x86_64")
  target_link_options(${LIBRARY_NAME} PRIVATE "-Wl,-z,max-page-size=16384")

  # https://github.com/llvm/llvm-project/pull/66042
  # the default linker is llvm lld since ndk r22, and gnu ld is used before ndk r22
  # so we need to add extra flags for 16K when ANDROID_NDK_MAJOR less than 22 and the linker is not lld
  # coz the ld linker need to set common-page-size=16384 to align all sections to 16K page size
  if(ANDROID_NDK_MAJOR LESS 22 AND NOT CMAKE_SHARED_LINKER_FLAGS MATCHES "-fuse-ld=lld" AND NOT CMAKE_MODULE_LINKER_FLAGS MATCHES "-fuse-ld=lld" AND NOT CMAKE_EXE_LINKER_FLAGS MATCHES "-fuse-ld=lld")
      message(STATUS "ndk version is ${ANDROID_NDK_MAJOR} and use ld linker, add -Wl,-z,common-page-size=16384")
      set_target_properties(${LIBRARY_NAME} PROPERTIES
          LINK_FLAGS "-Wl,-z,common-page-size=16384"
      )
  endif()
endif()

set(THIRD_PARTY_INCLUDE_DIRS
  "${CMAKE_CURRENT_SOURCE_DIR}/third_party/include/iris"
  "${CMAKE_CURRENT_SOURCE_DIR}/third_party/include/agora_rtc"
  "${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc_apm/include"
  "${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc_apm/include/webrtc-audio-processing-1"
  )

target_include_directories(${LIBRARY_NAME} PRIVATE
  "$<BUILD_INTERFACE:${THIRD_PARTY_INCLUDE_DIRS}>"
  "$<INSTALL_INTERFACE:$<INSTALL_PREFIX>/${CMAKE_INSTALL_INCLUDEDIR}>"
  )

# WebRTC AudioProcessing (AEC3) prebuilt static libraries.
# Only link if prebuilt libs exist for the current ABI (x86 is not built).
set(WEBRTC_APM_LIB_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc_apm/lib/${ANDROID_ABI}")

set(WEBRTC_APM_LIBS
    webrtc-audio-processing-1
    webrtc-audio-coding-1
    absl_base absl_container absl_crc absl_debugging absl_flags
    absl_hash absl_log absl_numeric absl_profiling absl_random
    absl_status absl_strings absl_synchronization absl_time absl_types)

if(EXISTS "${WEBRTC_APM_LIB_DIR}/libwebrtc-audio-processing-1.a")
  foreach(lib ${WEBRTC_APM_LIBS})
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION
      "${WEBRTC_APM_LIB_DIR}/lib${lib}.a")
  endforeach()
  target_link_libraries(${LIBRARY_NAME} PRIVATE ${WEBRTC_APM_LIBS})
  target_compile_definitions(${LIBRARY_NAME} PRIVATE HAS_WEBRTC_APM=1)
else()
  message(STATUS "WebRTC APM libs not found for ${ANDROID_ABI}, AEC disabled")
endif()
