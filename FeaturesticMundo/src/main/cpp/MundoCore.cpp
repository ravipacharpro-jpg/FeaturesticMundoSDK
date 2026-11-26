#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <android/log.h>

#define TAG "MundoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifndef __NR_memfd_create
#if defined(__aarch64__)
#define __NR_memfd_create 279
#elif defined(__arm__)
#define __NR_memfd_create 385
#endif
#endif

static std::string gHostPkg;
static std::string gActivePkg;
static std::string gSandboxDataDir;
static std::string gHostObbDir;
static std::atomic<bool> gStreamproofEnabled{false};

static std::string jstrToStr(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

static uintptr_t findModuleBase(const char *libName) {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return 0;
    char line[512];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, libName)) {
            unsigned long long start = 0;
            if (sscanf(line, "%llx-", &start) == 1) {
                base = static_cast<uintptr_t>(start);
                break;
            }
        }
    }
    fclose(fp);
    return base;
}

extern "C" JNIEXPORT void JNICALL
Java_com_featurestic_mundo_core_MundoNative_nativeInit(
        JNIEnv *env, jclass,
        jstring hostPkg, jstring activePkg,
        jstring sandboxDataDir, jstring hostObbDir) {
    gHostPkg = jstrToStr(env, hostPkg);
    gActivePkg = jstrToStr(env, activePkg);
    gSandboxDataDir = jstrToStr(env, sandboxDataDir);
    gHostObbDir = jstrToStr(env, hostObbDir);
    LOGI("MundoEngine initialized: host=%s active=%s", gHostPkg.c_str(), gActivePkg.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_featurestic_mundo_core_MundoNative_nativeLoadMemfdSo(
        JNIEnv *env, jclass, jbyteArray soBytes, jstring soname) {
    if (!soBytes) return JNI_FALSE;
    jsize len = env->GetArrayLength(soBytes);
    if (len <= 0) return JNI_FALSE;

    std::string name = jstrToStr(env, soname);
    if (name.empty()) name = "mundo_mem_plugin";

    int fd = static_cast<int>(syscall(__NR_memfd_create, name.c_str(), 1U));
    if (fd < 0) {
        LOGE("memfd_create failed");
        return JNI_FALSE;
    }

    jbyte *buf = env->GetByteArrayElements(soBytes, nullptr);
    if (!buf) {
        close(fd);
        return JNI_FALSE;
    }

    ssize_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, buf + written, static_cast<size_t>(len - written));
        if (n <= 0) break;
        written += n;
    }
    env->ReleaseByteArrayElements(soBytes, buf, JNI_ABORT);

    if (written != len) {
        close(fd);
        LOGE("memfd write incomplete");
        return JNI_FALSE;
    }

    char fdPath[64];
    snprintf(fdPath, sizeof(fdPath), "/proc/self/fd/%d", fd);
    void *handle = dlopen(fdPath, RTLD_NOW | RTLD_LOCAL);
    close(fd);

    if (!handle) {
        LOGE("dlopen memfd failed: %s", dlerror());
        return JNI_FALSE;
    }
    LOGI("Loaded RAM-only .so via memfd: %s", name.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_featurestic_mundo_core_MundoNative_nativeApplyAnogsOffsets(
        JNIEnv *env, jclass, jstring targetPkg, jstring offsetsCsv) {
    std::string target = jstrToStr(env, targetPkg);
    std::string csv = jstrToStr(env, offsetsCsv);

    // Strict target lock: ONLY patch com.pubg.imobile
    if (target != "com.pubg.imobile") {
        LOGI("Skipping anogs patch: target=%s is not com.pubg.imobile", target.c_str());
        return 0;
    }
    if (!gActivePkg.empty() && gActivePkg != "com.pubg.imobile") {
        LOGI("Skipping anogs patch: activePkg=%s is not com.pubg.imobile", gActivePkg.c_str());
        return 0;
    }
    if (csv.empty()) return 0;

    uintptr_t base = findModuleBase("libanogs.so");
    if (!base) {
        LOGI("libanogs.so not yet mapped");
        return 0;
    }

    static constexpr uint32_t kArm64RetInsn = 0xD65F03C0U;
    long pageSize = sysconf(_SC_PAGESIZE);
    int patched = 0;

    const char *p = csv.c_str();
    while (*p) {
        while (*p == ' ' || *p == ',' || *p == '\n' || *p == '\r') ++p;
        if (!*p) break;
        char *endp = nullptr;
        unsigned long long off = strtoull(p, &endp, 16);
        if (endp > p && off >= 0x1000 && off < 0x10000000ULL) {
            uintptr_t addr = base + static_cast<uintptr_t>(off);
            uintptr_t pageStart = addr & ~(static_cast<uintptr_t>(pageSize) - 1);
            if (mprotect(reinterpret_cast<void *>(pageStart), static_cast<size_t>(pageSize),
                         PROT_READ | PROT_WRITE | PROT_EXEC) == 0) {
                *reinterpret_cast<volatile uint32_t *>(addr) = kArm64RetInsn;
                __builtin___clear_cache(reinterpret_cast<char *>(addr),
                                        reinterpret_cast<char *>(addr + 4));
                mprotect(reinterpret_cast<void *>(pageStart), static_cast<size_t>(pageSize),
                         PROT_READ | PROT_EXEC);
                patched++;
            }
        }
        p = (endp && endp > p) ? endp : (p + 1);
    }
    LOGI("Applied %d ARM64 RET patches on libanogs.so for com.pubg.imobile", patched);
    return patched;
}

extern "C" JNIEXPORT void JNICALL
Java_com_featurestic_mundo_core_MundoNative_nativeSetStreamproof(
        JNIEnv *, jclass, jboolean enabled) {
    gStreamproofEnabled.store(enabled == JNI_TRUE, std::memory_order_release);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_featurestic_mundo_core_MundoNative_nativeIsStreamproofEnabled(JNIEnv *, jclass) {
    return gStreamproofEnabled.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_featurestic_mundo_core_MundoNative_nativeGetEngineVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF("FeaturesticMundoEngine/2.0.0");
}
