#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_apsu_gamestream_nativebridge_NativeBridge_version(
    JNIEnv *env,
    jobject /* this */) {
  return env->NewStringUTF("gamestream-native/0.1.0");
}
