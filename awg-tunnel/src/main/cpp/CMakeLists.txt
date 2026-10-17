cmake_minimum_required(VERSION 3.22.1)
project("DeyttAwg")

# Keep the upstream native build unchanged; the domain router uses a separate
# transport library while legacy app-only diagnostics still use its TUN backend.
if(NOT CMAKE_HOST_WIN32)
    add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/amneziawg-android/tunnel/tools" upstream-awg)
endif()

set(DEYTT_WG_OUTPUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libwg-go.so")
set(DEYTT_GO_OUTPUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libdeytt-awg.so")

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

if(CMAKE_HOST_WIN32)
    set(DEYTT_WG_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/amneziawg-android/tunnel/tools/libwg-go")
    set(DEYTT_PRECOMPILED_WG "${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/jniLibs/${ANDROID_ABI}/libwg-go.so")
    if(EXISTS "${DEYTT_PRECOMPILED_WG}")
        configure_file("${DEYTT_PRECOMPILED_WG}" "${DEYTT_WG_OUTPUT}" COPYONLY)
        add_custom_target(libwg-go.so)
    elseif(EXISTS "${DEYTT_WG_OUTPUT}")
        add_custom_target(libwg-go.so)
    else()
        if(NOT DEYTT_GO_EXECUTABLE)
            find_program(DEYTT_GO_EXECUTABLE go REQUIRED)
        endif()
        set(DEYTT_GOTMP "${CMAKE_BINARY_DIR}/gotmp")
        set(DEYTT_GOCACHE "${CMAKE_BINARY_DIR}/gocache")
        set(DEYTT_GOPATH "${CMAKE_BINARY_DIR}/gopath")
        file(MAKE_DIRECTORY "${DEYTT_GOTMP}")
        file(MAKE_DIRECTORY "${DEYTT_GOCACHE}")
        file(MAKE_DIRECTORY "${DEYTT_GOPATH}")
        file(GLOB DEYTT_WG_INPUTS CONFIGURE_DEPENDS "${DEYTT_WG_SOURCE}/*.go" "${DEYTT_WG_SOURCE}/*.c")
        add_custom_command(OUTPUT "${DEYTT_WG_OUTPUT}"
            COMMAND "${CMAKE_COMMAND}" -E env
                "TMP=${DEYTT_GOTMP}"
                "TEMP=${DEYTT_GOTMP}"
                "GOTMPDIR=${DEYTT_GOTMP}"
                "GOCACHE=${DEYTT_GOCACHE}"
                "GOPATH=${DEYTT_GOPATH}"
                GOOS=android "GOARCH=${DEYTT_GO_ARCH}" GOARM=7 CGO_ENABLED=1 GOTOOLCHAIN=local
                "CC=${CMAKE_C_COMPILER}"
                "CGO_CFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT}"
                "CGO_LDFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} -Wl,-z,max-page-size=16384 -Wl,-soname,libwg-go.so"
                "${DEYTT_GO_EXECUTABLE}" build -mod=readonly -tags=linux -trimpath -buildvcs=false -buildmode=c-shared
                "-ldflags=-X github.com/amnezia-vpn/amneziawg-go/v3/ipc.socketDirectory=/data/data/${ANDROID_PACKAGE_NAME}/cache/amneziawg -buildid="
                -o "${DEYTT_WG_OUTPUT}" .
            WORKING_DIRECTORY "${DEYTT_WG_SOURCE}"
            DEPENDS ${DEYTT_WG_INPUTS} "${DEYTT_WG_SOURCE}/go.mod" "${DEYTT_WG_SOURCE}/go.sum"
            VERBATIM)
        add_custom_target(libwg-go.so DEPENDS "${DEYTT_WG_OUTPUT}")
    endif()
endif()
set(DEYTT_GO_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../go")
set(DEYTT_PRECOMPILED_GO "${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/jniLibs/${ANDROID_ABI}/libdeytt-awg.so")
if(EXISTS "${DEYTT_PRECOMPILED_GO}")
    configure_file("${DEYTT_PRECOMPILED_GO}" "${DEYTT_GO_OUTPUT}" COPYONLY)
    add_custom_target(libdeytt-awg.so)
elseif(EXISTS "${DEYTT_GO_OUTPUT}")
    add_custom_target(libdeytt-awg.so)
else()
    if(NOT DEYTT_GO_EXECUTABLE)
        find_program(DEYTT_GO_EXECUTABLE go REQUIRED)
    endif()
    set(DEYTT_GOTMP "${CMAKE_BINARY_DIR}/gotmp")
    set(DEYTT_GOCACHE "${CMAKE_BINARY_DIR}/gocache")
    set(DEYTT_GOPATH "${CMAKE_BINARY_DIR}/gopath")
    file(MAKE_DIRECTORY "${DEYTT_GOTMP}")
    file(MAKE_DIRECTORY "${DEYTT_GOCACHE}")
    file(MAKE_DIRECTORY "${DEYTT_GOPATH}")
    file(GLOB_RECURSE DEYTT_GO_INPUTS CONFIGURE_DEPENDS "${DEYTT_GO_SOURCE}/*.go" "${DEYTT_GO_SOURCE}/*.c" "${DEYTT_GO_SOURCE}/*.h")
    add_custom_command(OUTPUT "${DEYTT_GO_OUTPUT}"
        COMMAND "${CMAKE_COMMAND}" -E env
            "TMP=${DEYTT_GOTMP}"
            "TEMP=${DEYTT_GOTMP}"
            "GOTMPDIR=${DEYTT_GOTMP}"
            "GOCACHE=${DEYTT_GOCACHE}"
            "GOPATH=${DEYTT_GOPATH}"
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
endif()
