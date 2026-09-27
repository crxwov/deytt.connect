#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "bridge.h"

extern int64_t deyttStart(char *, char *, char *, int, char *, char *, uintptr_t);
extern int deyttPort(int64_t);
extern void deyttStop(int64_t);

typedef struct {
    JavaVM *vm;
    jobject object;
    jmethodID method;
    int64_t native_handle;
} proxy_handle;

int deytt_protect_socket(uintptr_t raw, int fd) {
    proxy_handle *handle = (proxy_handle *)raw;
    JNIEnv *env = NULL;
    int attached = 0;
    jint status = (*handle->vm)->GetEnv(handle->vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*handle->vm)->AttachCurrentThread(handle->vm, &env, NULL) != JNI_OK) return 0;
        attached = 1;
    } else if (status != JNI_OK) return 0;
    jboolean result = (*env)->CallBooleanMethod(env, handle->object, handle->method, (jint)fd);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = JNI_FALSE; }
    if (attached) (*handle->vm)->DetachCurrentThread(handle->vm);
    return result == JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_space_deytt_awg_AwgProxyBackend_nativeStart(
        JNIEnv *env, jclass clazz, jstring settings, jstring addresses, jstring dns,
        jint mtu, jstring username, jstring password, jobject protector) {
    (void)clazz;
    proxy_handle *handle = calloc(1, sizeof(*handle));
    if (!handle) return 0;
    if ((*env)->GetJavaVM(env, &handle->vm) != JNI_OK) { free(handle); return 0; }
    handle->object = (*env)->NewGlobalRef(env, protector);
    jclass type = (*env)->GetObjectClass(env, protector);
    handle->method = (*env)->GetMethodID(env, type, "protect", "(I)Z");
    (*env)->DeleteLocalRef(env, type);
    if (!handle->object || !handle->method) {
        if (handle->object) (*env)->DeleteGlobalRef(env, handle->object);
        free(handle); return 0;
    }
    const char *a = (*env)->GetStringUTFChars(env, settings, NULL);
    const char *b = (*env)->GetStringUTFChars(env, addresses, NULL);
    const char *c = (*env)->GetStringUTFChars(env, dns, NULL);
    const char *d = (*env)->GetStringUTFChars(env, username, NULL);
    const char *e = (*env)->GetStringUTFChars(env, password, NULL);
    if (a && b && c && d && e)
        handle->native_handle = deyttStart((char *)a, (char *)b, (char *)c, mtu, (char *)d, (char *)e, (uintptr_t)handle);
    if (e) (*env)->ReleaseStringUTFChars(env, password, e);
    if (d) (*env)->ReleaseStringUTFChars(env, username, d);
    if (c) (*env)->ReleaseStringUTFChars(env, dns, c);
    if (b) (*env)->ReleaseStringUTFChars(env, addresses, b);
    if (a) (*env)->ReleaseStringUTFChars(env, settings, a);
    if (!handle->native_handle) { (*env)->DeleteGlobalRef(env, handle->object); free(handle); return 0; }
    return (jlong)(uintptr_t)handle;
}

JNIEXPORT jint JNICALL Java_space_deytt_awg_AwgProxyBackend_nativePort(JNIEnv *env, jclass clazz, jlong raw) {
    (void)env; (void)clazz;
    proxy_handle *handle = (proxy_handle *)(uintptr_t)raw;
    return handle ? deyttPort(handle->native_handle) : 0;
}

JNIEXPORT void JNICALL Java_space_deytt_awg_AwgProxyBackend_nativeStop(JNIEnv *env, jclass clazz, jlong raw) {
    (void)clazz;
    proxy_handle *handle = (proxy_handle *)(uintptr_t)raw;
    if (!handle) return;
    deyttStop(handle->native_handle);
    (*env)->DeleteGlobalRef(env, handle->object);
    free(handle);
}
