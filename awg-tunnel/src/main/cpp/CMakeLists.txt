cmake_minimum_required(VERSION 3.22.1)
project("DeyttAwg")

# Keep the upstream native build unchanged; the domain router uses a separate
# transport library while legacy app-only diagnostics still use its TUN backend.
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/amneziawg-android/tunnel/tools" upstream-awg)

find_program(DEYTT_GO_EXECUTABLE go REQUIRED)
set(DEYTT_GO_ARCH "${ANDROID_ARCH_NAME}")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(DEYTT_GO_ARCH arm64)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(DEYTT_GO_ARCH arm)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(DEYTT_GO_ARCH amd64)
elseif(ANDROID_ABI STREQUAL "x86")
    set(DEYTT_GO_ARCH 386)
endif()
set(DEYTT_GO_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../go")
file(GLOB_RECURSE DEYTT_GO_INPUTS CONFIGURE_DEPENDS "${DEYTT_GO_SOURCE}/*.go" "${DEYTT_GO_SOURCE}/*.c" "${DEYTT_GO_SOURCE}/*.h")
set(DEYTT_GO_OUTPUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libdeytt-awg.so")
add_custom_command(OUTPUT "${DEYTT_GO_OUTPUT}"
    COMMAND "${CMAKE_COMMAND}" -E env
        GOOS=android "GOARCH=${DEYTT_GO_ARCH}" GOARM=7 CGO_ENABLED=1
        "CC=${CMAKE_C_COMPILER}"
        "CGO_CFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT}"
        "CGO_LDFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} -Wl,-z,max-page-size=16384 -Wl,-soname,libdeytt-awg.so"
        "${DEYTT_GO_EXECUTABLE}" build -mod=readonly -trimpath -buildvcs=false -buildmode=c-shared
        -ldflags=-buildid= -o "${DEYTT_GO_OUTPUT}" .
    WORKING_DIRECTORY "${DEYTT_GO_SOURCE}"
    DEPENDS ${DEYTT_GO_INPUTS} "${DEYTT_GO_SOURCE}/go.mod" "${DEYTT_GO_SOURCE}/go.sum"
    VERBATIM)
add_custom_target(libdeytt-awg.so DEPENDS "${DEYTT_GO_OUTPUT}")
