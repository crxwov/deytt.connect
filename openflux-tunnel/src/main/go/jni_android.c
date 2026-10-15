#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "bridge.h"

extern int64_t openfluxStart(char *, char *, char *, char *, uintptr_t);
extern char *openfluxError(void);
extern int openfluxPort(int64_t);
extern int openfluxConnected(int64_t);
extern int64_t openfluxBytesSent(int64_t);
extern int64_t openfluxBytesReceived(int64_t);
extern void openfluxStop(int64_t);

typedef struct { JavaVM *vm; jobject object; jmethodID method; int64_t native_handle; } proxy_handle;

int openflux_protect_socket(uintptr_t raw, int fd) {
    proxy_handle *h = (proxy_handle *)raw;
    if (!h) return 0;
    JNIEnv *env = NULL;
    int attached = 0;
    jint status = (*h->vm)->GetEnv(h->vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*h->vm)->AttachCurrentThread(h->vm, &env, NULL) != JNI_OK) return 0;
        attached = 1;
    } else if (status != JNI_OK) return 0;
    jboolean result = (*env)->CallBooleanMethod(env, h->object, h->method, (jint)fd);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = JNI_FALSE; }
    if (attached) (*h->vm)->DetachCurrentThread(h->vm);
    return result == JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_space_deytt_openflux_OpenFluxProxyBackend_nativeStart(
        JNIEnv *env, jclass clazz, jstring profile, jstring username, jstring password, jstring dns, jobject protector) {
    (void)clazz;
    if (!profile || !username || !password || !dns || !protector) return 0;
    proxy_handle *h = calloc(1, sizeof(*h));
    if (!h) return 0;
    if ((*env)->GetJavaVM(env, &h->vm) != JNI_OK) { free(h); return 0; }
    h->object = (*env)->NewGlobalRef(env, protector);
    jclass type = (*env)->GetObjectClass(env, protector);
    h->method = type ? (*env)->GetMethodID(env, type, "protect", "(I)Z") : NULL;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
    if (type) (*env)->DeleteLocalRef(env, type);
    if (!h->object || !h->method) { if (h->object) (*env)->DeleteGlobalRef(env, h->object); free(h); return 0; }
    const char *a = (*env)->GetStringUTFChars(env, profile, NULL);
    const char *b = a ? (*env)->GetStringUTFChars(env, username, NULL) : NULL;
    const char *c = b ? (*env)->GetStringUTFChars(env, password, NULL) : NULL;
    const char *d = c ? (*env)->GetStringUTFChars(env, dns, NULL) : NULL;
    if (a && b && c && d) h->native_handle = openfluxStart((char *)a, (char *)b, (char *)c, (char *)d, (uintptr_t)h);
    if (d) (*env)->ReleaseStringUTFChars(env, dns, d);
    if (c) (*env)->ReleaseStringUTFChars(env, password, c);
    if (b) (*env)->ReleaseStringUTFChars(env, username, b);
    if (a) (*env)->ReleaseStringUTFChars(env, profile, a);
    if (!h->native_handle) {
        // Go's failed-start cleanup closes/drains its protector gate first.
        (*env)->DeleteGlobalRef(env, h->object); free(h); return 0;
    }
    return (jlong)(uintptr_t)h;
}

JNIEXPORT jstring JNICALL Java_space_deytt_openflux_OpenFluxProxyBackend_nativeError(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char *message = openfluxError();
    jstring result = (*env)->NewStringUTF(env, message && message[0] ? message : "OpenFlux activation failed");
    free(message);
    return result;
}

JNIEXPORT jint JNICALL Java_space_deytt_openflux_OpenFluxProxyBackend_nativePort(JNIEnv *env, jclass clazz, jlong raw) {
    (void)env; (void)clazz; proxy_handle *h = (proxy_handle *)(uintptr_t)raw; return h ? openfluxPort(h->native_handle) : 0;
}
JNIEXPORT jboolean JNICALL Java_space_deytt_openflux_OpenFluxProxyBackend_nativeConnected(JNIEnv *env, jclass clazz, jlong raw) {
    (void)env; (void)clazz; proxy_handle *h = (proxy_handle *)(uintptr_t)raw; return h && openfluxConnected(h->native_handle) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jlong JNICALL Java_space_deytt_openflux_OpenFluxProxyBackend_nativeBytesSent(JNIEnv *env, jclass clazz, jlong raw) {
    (void)env; (void)clazz; proxy_handle *h = (proxy_handle *)(uintptr_t)raw; return h ? openfluxBytesSent(h->native_handle) : 0;
}
JNIEXPORT jlong JNICALL Java_space_deytt_openflux_OpenFluxProxyBackend_nativeBytesReceived(JNIEnv *env, jclass clazz, jlong raw) {
    (void)env; (void)clazz; proxy_handle *h = (proxy_handle *)(uintptr_t)raw; return h ? openfluxBytesReceived(h->native_handle) : 0;
}
JNIEXPORT void JNICALL Java_space_deytt_openflux_OpenFluxProxyBackend_nativeStop(JNIEnv *env, jclass clazz, jlong raw) {
    (void)clazz; proxy_handle *h = (proxy_handle *)(uintptr_t)raw;
    if (!h) return;
    // Stop drains active callbacks and closes their Go gate. Late goroutines
    // see only that gate and never dereference this freed C/JNI owner.
    openfluxStop(h->native_handle);
    (*env)->DeleteGlobalRef(env, h->object);
    free(h);
}
