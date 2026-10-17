cmake_minimum_required(VERSION 3.22.1)
project("DeyttOpenFlux")

set(OPENFLUX_GO_OUTPUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libopenflux_jni.so")
if(EXISTS "${OPENFLUX_GO_OUTPUT}")
    add_custom_target(libopenflux_jni.so)
else()
    if(NOT OPENFLUX_GO_EXECUTABLE OR OPENFLUX_GO_EXECUTABLE STREQUAL "go")
        find_program(OPENFLUX_GO_EXECUTABLE go REQUIRED)
    endif()
    execute_process(COMMAND "${OPENFLUX_GO_EXECUTABLE}" version OUTPUT_VARIABLE OPENFLUX_GO_VERSION RESULT_VARIABLE OPENFLUX_GO_STATUS)
    if(NOT OPENFLUX_GO_STATUS EQUAL 0 OR NOT OPENFLUX_GO_VERSION MATCHES "go1\\.26\\.6 ")
        message(FATAL_ERROR "OpenFlux requires pinned Go1.26.6; set -PopenfluxGoExecutable=<absolute go executable>")
    endif()
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(OPENFLUX_GO_ARCH arm64)
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(OPENFLUX_GO_ARCH arm)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(OPENFLUX_GO_ARCH amd64)
    else()
        message(FATAL_ERROR "Unsupported OpenFlux ABI ${ANDROID_ABI}")
    endif()
    set(OPENFLUX_GO_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../go")
    set(OPENFLUX_CORE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/openflux-core")
    file(GLOB_RECURSE OPENFLUX_GO_INPUTS CONFIGURE_DEPENDS "${OPENFLUX_GO_SOURCE}/*.go" "${OPENFLUX_GO_SOURCE}/*.c" "${OPENFLUX_GO_SOURCE}/*.h" "${OPENFLUX_CORE}/*.go")
    add_custom_command(OUTPUT "${OPENFLUX_GO_OUTPUT}"
        COMMAND "${CMAKE_COMMAND}" -E env
            GOOS=android "GOARCH=${OPENFLUX_GO_ARCH}" GOARM=7 CGO_ENABLED=1 GOTOOLCHAIN=local
            "CC=${CMAKE_C_COMPILER}"
            "CGO_CFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT}"
            "CGO_LDFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} -Wl,-z,max-page-size=16384 -Wl,-soname,libopenflux_jni.so"
            "${OPENFLUX_GO_EXECUTABLE}" build -mod=readonly -trimpath -buildvcs=false -buildmode=c-shared
            -ldflags=-buildid= -o "${OPENFLUX_GO_OUTPUT}" .
        WORKING_DIRECTORY "${OPENFLUX_GO_SOURCE}"
        DEPENDS ${OPENFLUX_GO_INPUTS} "${OPENFLUX_GO_SOURCE}/go.mod" "${OPENFLUX_GO_SOURCE}/go.sum" "${OPENFLUX_CORE}/go.mod" "${OPENFLUX_CORE}/go.sum"
        VERBATIM)
    add_custom_target(libopenflux_jni.so DEPENDS "${OPENFLUX_GO_OUTPUT}")
endif()
