#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <cstdarg>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <link.h>
#include <elf.h>
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
static std::atomic<bool> gVfsHooked{false};

static std::string jstrToStr(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

// ---------------------------------------------------------------------------
// VFS Path Redirector
// ---------------------------------------------------------------------------
static std::string redirectPath(const char *path) {
    if (!path || gActivePkg.empty() || gSandboxDataDir.empty()) return "";
    std::string p(path);

    std::string dataPrefix1 = "/data/data/" + gActivePkg;
    std::string dataPrefix2 = "/data/user/0/" + gActivePkg;

    if (p.rfind(dataPrefix1, 0) == 0) {
        return gSandboxDataDir + p.substr(dataPrefix1.length());
    }
    if (p.rfind(dataPrefix2, 0) == 0) {
        return gSandboxDataDir + p.substr(dataPrefix2.length());
    }
    return "";
}

static int (*orig_openat)(int dirfd, const char *pathname, int flags, ...) = nullptr;
static int (*orig_open)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_access)(const char *pathname, int mode) = nullptr;
static int (*orig_faccessat)(int dirfd, const char *pathname, int mode, int flags) = nullptr;

static int hook_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    std::string redir = redirectPath(pathname);
    const char *target = redir.empty() ? pathname : redir.c_str();
    if (orig_openat) {
        return (flags & (O_CREAT | O_TMPFILE))
               ? orig_openat(dirfd, target, flags, mode)
               : orig_openat(dirfd, target, flags);
    }
    return static_cast<int>(syscall(__NR_openat, dirfd, target, flags, mode));
}

static int hook_open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    std::string redir = redirectPath(pathname);
    const char *target = redir.empty() ? pathname : redir.c_str();
    if (orig_open) {
        return (flags & (O_CREAT | O_TMPFILE))
               ? orig_open(target, flags, mode)
               : orig_open(target, flags);
    }
    return static_cast<int>(syscall(__NR_openat, AT_FDCWD, target, flags, mode));
}

static int hook_access(const char *pathname, int mode) {
    std::string redir = redirectPath(pathname);
    const char *target = redir.empty() ? pathname : redir.c_str();
    if (orig_access) return orig_access(target, mode);
    return static_cast<int>(syscall(__NR_faccessat, AT_FDCWD, target, mode, 0));
}

static int hook_faccessat(int dirfd, const char *pathname, int mode, int flags) {
    std::string redir = redirectPath(pathname);
    const char *target = redir.empty() ? pathname : redir.c_str();
    if (orig_faccessat) return orig_faccessat(dirfd, target, mode, flags);
    return static_cast<int>(syscall(__NR_faccessat, dirfd, target, mode, flags));
}

// ---------------------------------------------------------------------------
// Self-contained ARM64 ELF64 GOT/PLT Hook Engine
// ---------------------------------------------------------------------------
struct PltHookEntry {
    const char *symName;
    void *newFunc;
    void **origOut;
};

static void patchDynamicRelocs(ElfW(Addr) loadBias, const ElfW(Rela) *relas, size_t count,
                               const ElfW(Sym) *symtab, const char *strtab,
                               const PltHookEntry *entries, size_t entryCount, long pageSize) {
    if (!relas || !symtab || !strtab) return;
    for (size_t i = 0; i < count; ++i) {
        const ElfW(Rela) &r = relas[i];
        uint32_t symIdx = ELF64_R_SYM(r.r_info);
        if (symIdx == 0) continue;
        const char *name = strtab + symtab[symIdx].st_name;
        if (!name) continue;
        for (size_t k = 0; k < entryCount; ++k) {
            if (strcmp(name, entries[k].symName) == 0) {
                void **gotSlot = reinterpret_cast<void **>(loadBias + r.r_offset);
                if (*gotSlot == entries[k].newFunc) continue;
                uintptr_t pageStart = reinterpret_cast<uintptr_t>(gotSlot) & ~(static_cast<uintptr_t>(pageSize) - 1);
                if (mprotect(reinterpret_cast<void *>(pageStart), static_cast<size_t>(pageSize), PROT_READ | PROT_WRITE) == 0) {
                    if (entries[k].origOut && !(*entries[k].origOut)) {
                        *entries[k].origOut = *gotSlot;
                    }
                    *gotSlot = entries[k].newFunc;
                    __builtin___clear_cache(reinterpret_cast<char *>(gotSlot),
                                            reinterpret_cast<char *>(gotSlot) + sizeof(void *));
                }
            }
        }
    }
}

static int phdrPltCallback(struct dl_phdr_info *info, size_t, void *) {
    if (!info || !info->dlpi_name) return 0;
    // Do not hook our own engine or linker/libc itself
    if (strstr(info->dlpi_name, "libMundoEngine.so") ||
        strstr(info->dlpi_name, "libc.so") ||
        strstr(info->dlpi_name, "linker")) {
        return 0;
    }

    const ElfW(Dyn) *dyn = nullptr;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const ElfW(Dyn) *>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    const ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    const ElfW(Rela) *jmprel = nullptr;
    const ElfW(Rela) *rela = nullptr;
    size_t pltRelSz = 0;
    size_t relaSz = 0;
    bool isRela = true;

    for (; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_SYMTAB: symtab = reinterpret_cast<const ElfW(Sym) *>(info->dlpi_addr + dyn->d_un.d_ptr); break;
            case DT_STRTAB: strtab = reinterpret_cast<const char *>(info->dlpi_addr + dyn->d_un.d_ptr); break;
            case DT_JMPREL: jmprel = reinterpret_cast<const ElfW(Rela) *>(info->dlpi_addr + dyn->d_un.d_ptr); break;
            case DT_PLTRELSZ: pltRelSz = dyn->d_un.d_val; break;
            case DT_PLTREL: isRela = (dyn->d_un.d_val == DT_RELA); break;
            case DT_RELA: rela = reinterpret_cast<const ElfW(Rela) *>(info->dlpi_addr + dyn->d_un.d_ptr); break;
            case DT_RELASZ: relaSz = dyn->d_un.d_val; break;
            default: break;
        }
    }

    if (!symtab || !strtab || !isRela) return 0;

    PltHookEntry hooks[] = {
        {"openat", reinterpret_cast<void *>(hook_openat), reinterpret_cast<void **>(&orig_openat)},
        {"open", reinterpret_cast<void *>(hook_open), reinterpret_cast<void **>(&orig_open)},
        {"access", reinterpret_cast<void *>(hook_access), reinterpret_cast<void **>(&orig_access)},
        {"faccessat", reinterpret_cast<void *>(hook_faccessat), reinterpret_cast<void **>(&orig_faccessat)}
    };
    long pageSize = sysconf(_SC_PAGESIZE);

    if (jmprel && pltRelSz > 0) {
        patchDynamicRelocs(info->dlpi_addr, jmprel, pltRelSz / sizeof(ElfW(Rela)), symtab, strtab, hooks, 4, pageSize);
    }
    if (rela && relaSz > 0) {
        patchDynamicRelocs(info->dlpi_addr, rela, relaSz / sizeof(ElfW(Rela)), symtab, strtab, hooks, 4, pageSize);
    }
    return 0;
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
Java_com_featurestic_mundo_core_MundoNative_nativeInstallVfsHooks(JNIEnv *, jclass) {
    if (gVfsHooked.exchange(true)) return JNI_TRUE;
    dl_iterate_phdr(phdrPltCallback, nullptr);
    LOGI("Installed native ARM64 PLT/GOT VFS hooks");
    return JNI_TRUE;
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
    return env->NewStringUTF("FeaturesticMundoEngine/1.1.0-ScratchPLT");
}
