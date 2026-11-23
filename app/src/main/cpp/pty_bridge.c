#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <signal.h>
#include <string.h>
#include <stdio.h>

JNIEXPORT jintArray JNICALL
Java_com_moviebox_android_PtyBridge_startProcess(
        JNIEnv *env,
        jobject thiz,
        jstring jBinaryPath,
        jstring jHomeDir,
        jstring jBinDir,
        jint rows,
        jint cols) {
    const char *binaryPath = (*env)->GetStringUTFChars(env, jBinaryPath, NULL);
    const char *homeDir = (*env)->GetStringUTFChars(env, jHomeDir, NULL);
    const char *binDir = (*env)->GetStringUTFChars(env, jBinDir, NULL);

    int masterFd = posix_openpt(O_RDWR | O_NOCTTY);
    if (masterFd < 0 || grantpt(masterFd) < 0 || unlockpt(masterFd) < 0) {
        if (masterFd >= 0) close(masterFd);
        (*env)->ReleaseStringUTFChars(env, jBinaryPath, binaryPath);
        (*env)->ReleaseStringUTFChars(env, jHomeDir, homeDir);
        (*env)->ReleaseStringUTFChars(env, jBinDir, binDir);
        return NULL;
    }

    char slaveName[128];
    if (ptsname_r(masterFd, slaveName, sizeof(slaveName)) != 0) {
        close(masterFd);
        (*env)->ReleaseStringUTFChars(env, jBinaryPath, binaryPath);
        (*env)->ReleaseStringUTFChars(env, jHomeDir, homeDir);
        (*env)->ReleaseStringUTFChars(env, jBinDir, binDir);
        return NULL;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(masterFd, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid == 0) {
        close(masterFd);
        setsid();
        int slaveFd = open(slaveName, O_RDWR);
        if (slaveFd < 0) _exit(127);
        ioctl(slaveFd, TIOCSCTTY, 0);

        dup2(slaveFd, STDIN_FILENO);
        dup2(slaveFd, STDOUT_FILENO);
        dup2(slaveFd, STDERR_FILENO);
        if (slaveFd > 2) close(slaveFd);

        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("HOME", homeDir, 1);
        setenv("XDG_DATA_HOME", homeDir, 1);
        setenv("XDG_CONFIG_HOME", homeDir, 1);

        char pathBuf[1024];
        snprintf(pathBuf, sizeof(pathBuf), "%s:/system/bin:/system/xbin", binDir);
        setenv("PATH", pathBuf, 1);
        chdir(homeDir);

        char *argv[] = {(char *)binaryPath, NULL};
        execv(binaryPath, argv);
        _exit(127);
    }

    (*env)->ReleaseStringUTFChars(env, jBinaryPath, binaryPath);
    (*env)->ReleaseStringUTFChars(env, jHomeDir, homeDir);
    (*env)->ReleaseStringUTFChars(env, jBinDir, binDir);

    jint result[2] = {masterFd, (jint)pid};
    jintArray jResult = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, jResult, 0, 2, result);
    return jResult;
}

JNIEXPORT jbyteArray JNICALL
Java_com_moviebox_android_PtyBridge_readOutput(JNIEnv *env, jobject thiz, jint fd) {
    char buf[8192];
    ssize_t n = read(fd, buf, sizeof(buf));
    if (n <= 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)n);
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)n, (jbyte *)buf);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_moviebox_android_PtyBridge_writeInput(JNIEnv *env, jobject thiz, jint fd, jbyteArray data) {
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    write(fd, bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_moviebox_android_PtyBridge_resizePty(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_moviebox_android_PtyBridge_closePty(JNIEnv *env, jobject thiz, jint fd, jint pid) {
    if (pid > 0) kill(pid, SIGKILL);
    if (fd >= 0) close(fd);
}
