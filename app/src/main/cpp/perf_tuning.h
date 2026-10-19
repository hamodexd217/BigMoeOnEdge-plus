// perf_tuning.h - keep the engine's compute threads on the fast CPU cores (and slightly favoured).
//
// Why: ggml's compute threads wait for each other at every graph step, so one thread scheduled onto a
// slow "little" core slows all of them. ggml creates its worker threads fresh inside each graph compute,
// from the calling thread, and (with its default empty cpumask / NORMAL priority) leaves the inherited CPU
// mask and nice value alone. Linux threads inherit both from their creator, so setting them on the
// engine's calling thread for the duration of a generate call carries to every compute thread.
//
// What it cannot do: an app cannot set CPU frequency or governor (that needs root); the kernel/vendor
// frequency ceiling and thermal limits are untouched. This only controls placement and scheduling weight.
//
// Fails safe: if the per-core maximum frequencies cannot be read, the cores look homogeneous, there are
// fewer fast cores than compute threads, or the kernel refuses the mask, nothing is changed.
#pragma once

#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <string>

namespace bmoe_perf {

struct CorePlan {
    bool usable = false;     // true: pinning to `mask` is worthwhile and safe
    cpu_set_t mask;          // the fast cores (valid when usable)
    int n_selected = 0;      // fast cores
    int n_total = 0;         // all cores examined
    std::string note;        // one-line, human readable reason / result, for the log

    CorePlan() { CPU_ZERO(&mask); }
};

// Reads <sysfs_root>/cpu<N>/cpufreq/cpuinfo_max_freq for N in [0, n_cpus). A core is "fast" when its maximum
// frequency is >= frac * the highest maximum. Usable only when the set is a proper, non-empty subset of the
// cores and has at least min_cores members.
inline CorePlan plan_fast_cores(const std::string & sysfs_root, int n_cpus, int min_cores, double frac = 0.75) {
    CorePlan plan;
    plan.n_total = n_cpus;
    if (n_cpus <= 1 || n_cpus > CPU_SETSIZE) {
        plan.note = "single/unknown core count, not pinning";
        return plan;
    }
    long freq[CPU_SETSIZE] = {0};
    long hi = 0;
    for (int i = 0; i < n_cpus; i++) {
        const std::string path = sysfs_root + "/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
        FILE * f = std::fopen(path.c_str(), "r");
        long v = 0;
        if (f == nullptr || std::fscanf(f, "%ld", &v) != 1 || v <= 0) {
            if (f != nullptr) std::fclose(f);
            plan.note = "cannot read max frequency of cpu" + std::to_string(i) + ", not pinning";
            return plan;
        }
        std::fclose(f);
        freq[i] = v;
        if (v > hi) hi = v;
    }
    const double thr = frac * static_cast<double>(hi);
    std::string list;
    for (int i = 0; i < n_cpus; i++) {
        if (static_cast<double>(freq[i]) >= thr) {
            CPU_SET(i, &plan.mask);
            plan.n_selected++;
            list += (list.empty() ? "" : ",") + std::to_string(i);
        }
    }
    if (plan.n_selected == n_cpus) {
        plan.note = "cores look homogeneous, not pinning";
    } else if (plan.n_selected < min_cores) {
        plan.note = "only " + std::to_string(plan.n_selected) + " fast cores for " + std::to_string(min_cores) +
                    " compute threads, not pinning";
    } else {
        plan.usable = true;
        plan.note = "fast cores {" + list + "} of " + std::to_string(n_cpus);
    }
    return plan;
}

inline pid_t current_tid() { return static_cast<pid_t>(::syscall(SYS_gettid)); }

// RAII: applies the plan's CPU mask and a nice boost to the *calling thread*, restores both on destruction.
class ScopedFastCores {
public:
    ScopedFastCores(const CorePlan & plan, int nice_value) : tid_(current_tid()) {
        CPU_ZERO(&old_mask_);
        if (!plan.usable) return;
        if (::sched_getaffinity(tid_, sizeof(old_mask_), &old_mask_) == 0 &&
            ::sched_setaffinity(tid_, sizeof(plan.mask), &plan.mask) == 0) {
            affinity_applied_ = true;
        }
        errno = 0;
        const int cur = ::getpriority(PRIO_PROCESS, static_cast<id_t>(tid_));
        if (errno == 0 && cur > nice_value) {
            old_nice_ = cur;
            if (::setpriority(PRIO_PROCESS, static_cast<id_t>(tid_), nice_value) == 0) nice_applied_ = true;
        }
    }
    ~ScopedFastCores() {
        if (affinity_applied_) ::sched_setaffinity(tid_, sizeof(old_mask_), &old_mask_);
        if (nice_applied_) ::setpriority(PRIO_PROCESS, static_cast<id_t>(tid_), old_nice_);
    }
    ScopedFastCores(const ScopedFastCores &) = delete;
    ScopedFastCores & operator=(const ScopedFastCores &) = delete;

    bool affinity_applied() const { return affinity_applied_; }
    bool nice_applied() const { return nice_applied_; }

private:
    pid_t tid_;
    cpu_set_t old_mask_;
    int old_nice_ = 0;
    bool affinity_applied_ = false;
    bool nice_applied_ = false;
};

}  // namespace bmoe_perf
