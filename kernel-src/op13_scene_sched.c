// SPDX-License-Identifier: GPL-2.0-only
/*
 * OP13 Scene Scheduler SM8750 local policy engine.
 *
 * Design constraints:
 *   - one min/max freq_qos pair for each physical cpufreq policy;
 *   - no timer and no load sampler in the kernel module;
 *   - one atomic userspace policy commit through /proc/op13_scene_sched;
 *   - battery temperature is refreshed only on power_supply notifications;
 *   - system saver and thermal limits are hard caps and cannot be raised by
 *     a game, Game Turbo or performance-mode floor.
 */

#include <linux/cpufreq.h>
#include <linux/cpumask.h>
#include <linux/errno.h>
#include <linux/hashtable.h>
#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/list.h>
#include <linux/kobject.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/pid.h>
#include <linux/power_supply.h>
#include <linux/proc_fs.h>
#include <linux/rcupdate.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/seq_file.h>
#include <linux/string.h>
#include <linux/spinlock.h>
#include <linux/tracepoint.h>
#include <linux/uaccess.h>
#include <linux/workqueue.h>

#include <trace/events/task.h>
#include <trace/hooks/sched.h>

#define HS_NAME "op13_scene_sched"
#define HS_CLUSTERS 2
#define HS_MODES 4
#define HS_OWNER_LEN 192
#define HS_CONTROL_LEN 320
#define HS_THREAD_NAME "op13_scene_threads"
#define HS_THREAD_INPUT_LEN 512
#define HS_MAX_TRACKED 4
#define HS_ROLE_LEN 24
#define HS_GUARD_HASH_BITS 7

enum hs_mode {
	HS_POWERSAVE = 0,
	HS_BALANCE = 1,
	HS_PERFORMANCE = 2,
	HS_FAST = 3,
};

enum hs_profile {
	HS_PROFILE_APP = 0,
	HS_PROFILE_GAME = 1,
};

enum hs_thermal_state {
	HS_THERMAL_CLEAR = 0,
	HS_THERMAL_WARM,
	HS_THERMAL_HOT,
	HS_THERMAL_FPS60,
};

struct hs_cluster {
	unsigned int cpu;
	struct cpufreq_policy *policy;
	struct freq_qos_request min_req;
	struct freq_qos_request max_req;
	bool min_active;
	bool max_active;
	unsigned int applied_min;
	unsigned int applied_max;
};

struct hs_tracked_process {
	pid_t tgid;
	int priority;
};

struct hs_affinity_record {
	struct list_head node;
	struct hlist_node guard_node;
	struct task_struct *task;
	cpumask_t applied_mask;
	pid_t tgid;
	pid_t tid;
	bool guard_linked;
	bool native_persistent;
	char role[HS_ROLE_LEN];
};

struct hs_thread_event {
	struct work_struct work;
	pid_t tgid;
	pid_t tid;
	u64 start_boottime;
	char comm[TASK_COMM_LEN];
};

static struct hs_cluster clusters[HS_CLUSTERS] = {
	{ .cpu = 0 },
	{ .cpu = 6 },
};

/*
 * Conservative SM8750 LP tables.  Values are exact entries exposed by the
 * target OnePlus 13/Ace 6 cpufreq tables.  App and game profiles are separate
 * so a normal performance request does not inherit a game's minimum vote.
 */
static unsigned int app_cpu0_min[HS_MODES] = {
	556800, 556800, 748800, 960000
};
static unsigned int app_cpu0_max[HS_MODES] = {
	1785600, 1996800, 2400000, 2745600
};
static unsigned int app_cpu6_min[HS_MODES] = {
	1017600, 1017600, 1017600, 1017600
};
static unsigned int app_cpu6_max[HS_MODES] = {
	1958400, 2246400, 2841600, 3283200
};
static unsigned int game_cpu0_min[HS_MODES] = {
	556800, 748800, 748800, 1152000
};
static unsigned int game_cpu0_max[HS_MODES] = {
	1785600, 1996800, 2400000, 2400000
};
static unsigned int game_cpu6_min[HS_MODES] = {
	1017600, 1017600, 1017600, 1401600
};
static unsigned int game_cpu6_max[HS_MODES] = {
	2438400, 2649600, 3072000, 3283200
};
/* Scene LP app-state inactive/idle caps.  The fast inactive scheme keeps its
 * published fast ceiling; the other three schemes use Scene's inactive/idle
 * limiter tables. */
static unsigned int inactive_cpu0_min[HS_MODES] = {
	556800, 556800, 556800, 960000
};
static unsigned int inactive_cpu0_max[HS_MODES] = {
	1555200, 1785600, 1785600, 2745600
};
static unsigned int inactive_cpu6_min[HS_MODES] = {
	1017600, 1017600, 1017600, 1017600
};
static unsigned int inactive_cpu6_max[HS_MODES] = {
	1689600, 1958400, 1958400, 3283200
};
static unsigned int extreme_max[HS_CLUSTERS] = { 1555200, 1958400 };

module_param_array(app_cpu0_min, uint, NULL, 0444);
module_param_array(app_cpu0_max, uint, NULL, 0444);
module_param_array(app_cpu6_min, uint, NULL, 0444);
module_param_array(app_cpu6_max, uint, NULL, 0444);
module_param_array(game_cpu0_min, uint, NULL, 0444);
module_param_array(game_cpu0_max, uint, NULL, 0444);
module_param_array(game_cpu6_min, uint, NULL, 0444);
module_param_array(game_cpu6_max, uint, NULL, 0444);
module_param_array(inactive_cpu0_min, uint, NULL, 0444);
module_param_array(inactive_cpu0_max, uint, NULL, 0444);
module_param_array(inactive_cpu6_min, uint, NULL, 0444);
module_param_array(inactive_cpu6_max, uint, NULL, 0444);
module_param_array(extreme_max, uint, NULL, 0444);

static int requested_mode = -1;
static int requested_profile = HS_PROFILE_APP;
static int daily_mode = HS_BALANCE;
static int saver_state;
static int extreme_state;
static int screen_active = 1;
static int battery_temp_decic = -1;
static int thermal_state;
static int effective_mode = HS_BALANCE;
static int effective_profile = HS_PROFILE_APP;
static int fps_cap;
static bool fas_active;
static unsigned int fas_cpu0_max;
static unsigned int fas_cpu6_max;
static unsigned long fas_updates;
static int announced_effective_mode = -1;
static int announced_effective_profile = -1;
static int announced_thermal_state = -1;
static int announced_fps_cap = -1;
static int announced_extreme_state = -1;
static int warm_enter = 480;
static int warm_exit = 470;
static int hot_enter = 510;
static int hot_exit = 500;
static int fps60_enter = 520;
static int fps60_exit = 510;
static char owner[HS_OWNER_LEN] = "daily";
static bool hs_ready;
static unsigned long control_updates;
static unsigned long power_events;
static unsigned long power_coalesced;
static unsigned long apply_runs;
static unsigned long last_power_queue_jiffies;
static int last_qos_error;
static DEFINE_MUTEX(hs_lock);
static struct notifier_block power_nb;
static struct work_struct apply_work;
static struct proc_dir_entry *status_entry;
static struct proc_dir_entry *thread_entry;

/*
 * Scene-compatible thread placement transport.
 *
 * The kernel deliberately does not parse package policy.  The already
 * event-driven HyperOS bridge resolves visible packages and Scene's JSON
 * rules, then submits only validated TID/mask pairs here.  The tracepoints are
 * registered only while at least one game process is tracked, so the idle
 * path has no task-creation/rename callback at all.
 */
static struct hs_tracked_process tracked[HS_MAX_TRACKED];
static int tracked_count;
static bool thread_traces_active;
static DEFINE_MUTEX(hs_thread_lock);
static LIST_HEAD(hs_affinity_records);
static DEFINE_HASHTABLE(hs_affinity_guards, HS_GUARD_HASH_BITS);
static DEFINE_RAW_SPINLOCK(hs_guard_lock);
static bool hs_guard_hook_active;
static unsigned int hs_guard_records;
static struct workqueue_struct *hs_thread_wq;
static atomic64_t thread_events = ATOMIC64_INIT(0);
static atomic64_t thread_event_drops = ATOMIC64_INIT(0);
static atomic64_t thread_uevent_errors = ATOMIC64_INIT(0);
static unsigned long thread_apply_requests;
static unsigned long thread_apply_changes;
static unsigned long thread_apply_skips;
static unsigned long thread_apply_errors;
static unsigned long thread_restore_changes;
static unsigned long thread_restore_errors;
static unsigned long hs_guard_register_errors;
static atomic64_t hs_guard_events = ATOMIC64_INIT(0);
static atomic64_t hs_guard_rejects = ATOMIC64_INIT(0);
static atomic64_t hs_guard_matches = ATOMIC64_INIT(0);
static atomic64_t thread_inherit_candidates = ATOMIC64_INIT(0);
static atomic64_t thread_inherit_resets = ATOMIC64_INIT(0);
static atomic64_t thread_inherit_fallbacks = ATOMIC64_INIT(0);
static atomic64_t thread_inherit_errors = ATOMIC64_INIT(0);
static atomic64_t thread_dead_pruned = ATOMIC64_INIT(0);
typedef long (*hs_sched_setaffinity_t)(pid_t pid,
				       const struct cpumask *in_mask);
static hs_sched_setaffinity_t hs_sched_setaffinity_fn;
static int hs_sched_setaffinity_resolve_error;
static int hs_last_native_affinity_error;
static unsigned long thread_native_applies;
static unsigned long thread_native_restores;
static unsigned long thread_native_fallbacks;

static void hs_prune_dead_affinity_records_locked(void);

static const char *hs_mode_name(int mode)
{
	static const char * const names[HS_MODES] = {
		"powersave", "balance", "performance", "fast"
	};

	return mode >= 0 && mode < HS_MODES ? names[mode] : "invalid";
}

static const char *hs_profile_name(int profile)
{
	return profile == HS_PROFILE_GAME ? "game" : "app";
}

static int hs_clamp_mode(int mode)
{
	return clamp(mode, (int)HS_POWERSAVE, (int)HS_FAST);
}

static int hs_tracked_priority(pid_t tgid)
{
	int i;

	if (tgid <= 0)
		return -1;
	for (i = 0; i < READ_ONCE(tracked_count); i++) {
		if (READ_ONCE(tracked[i].tgid) == tgid)
			return READ_ONCE(tracked[i].priority);
	}
	return -1;
}

static bool hs_tgid_in_set(pid_t tgid,
			   const struct hs_tracked_process *set, int count)
{
	int i;

	for (i = 0; i < count; i++)
		if (set[i].tgid == tgid)
			return true;
	return false;
}

static void hs_sanitize_comm(char *comm)
{
	int i;

	for (i = 0; i < TASK_COMM_LEN && comm[i]; i++) {
		unsigned char value = comm[i];

		if (value < 0x20 || value == 0x7f)
			comm[i] = '_';
	}
}

static void hs_thread_event_workfn(struct work_struct *work)
{
	struct hs_thread_event *event = container_of(work,
			struct hs_thread_event, work);
	char tgid_env[32];
	char tid_env[32];
	char priority_env[32];
	char start_env[48];
	char comm_env[48];
	char *envp[] = {
		"HYPER_SCHED_THREAD=1", tgid_env, tid_env,
		priority_env, start_env, comm_env, NULL
	};
	int priority;
	int ret;

	mutex_lock(&hs_thread_lock);
	hs_prune_dead_affinity_records_locked();
	priority = hs_tracked_priority(event->tgid);
	mutex_unlock(&hs_thread_lock);
	if (priority < 0)
		goto out;
	scnprintf(tgid_env, sizeof(tgid_env), "TGID=%d", event->tgid);
	scnprintf(tid_env, sizeof(tid_env), "TID=%d", event->tid);
	scnprintf(priority_env, sizeof(priority_env), "PRIORITY=%d", priority);
	scnprintf(start_env, sizeof(start_env), "START_BOOTTIME=%llu",
		  (unsigned long long)event->start_boottime);
	scnprintf(comm_env, sizeof(comm_env), "COMM=%s", event->comm);
	ret = kobject_uevent_env(&THIS_MODULE->mkobj.kobj, KOBJ_CHANGE, envp);
	if (ret)
		atomic64_inc(&thread_uevent_errors);
out:
	kfree(event);
}

static void hs_queue_thread_event(struct task_struct *task, const char *comm)
{
	struct hs_thread_event *event;
	pid_t tgid;

	if (!READ_ONCE(thread_traces_active) || !task)
		return;
	tgid = READ_ONCE(task->tgid);
	if (hs_tracked_priority(tgid) < 0)
		return;
	event = kzalloc(sizeof(*event), GFP_ATOMIC);
	if (!event) {
		atomic64_inc(&thread_event_drops);
		return;
	}
	INIT_WORK(&event->work, hs_thread_event_workfn);
	event->tgid = tgid;
	event->tid = READ_ONCE(task->pid);
	event->start_boottime = READ_ONCE(task->start_boottime);
	strscpy(event->comm, comm ? comm : task->comm,
		sizeof(event->comm));
	hs_sanitize_comm(event->comm);
	atomic64_inc(&thread_events);
	if (!queue_work(hs_thread_wq, &event->work)) {
		atomic64_inc(&thread_event_drops);
		kfree(event);
	}
}

static void hs_task_newtask_probe(void *unused, struct task_struct *task,
				  unsigned long clone_flags)
{
	struct hs_affinity_record *record;
	unsigned long flags;
	bool inherited_from_guard = false;
	long ret = -EOPNOTSUPP;

	/* A user affinity mask is inherited from the creating thread.  Without
	 * clearing it here, pinning a game main/render thread also pins every
	 * later network, audio and worker child to the same two CPUs.  task_newtask
	 * runs before the child is woken, so restore an unrestricted user mask
	 * synchronously.  Do not publish a classification event here: before its
	 * first run the child still carries its creator's comm, so a worker born
	 * from UnityMain would be falsely pinned as UnityMain.  A later task_rename
	 * event contains the final pthread name and applies any real Scene rule. */
	if (task && READ_ONCE(thread_traces_active) && current &&
	    READ_ONCE(task->tgid) == READ_ONCE(current->tgid) &&
	    hs_tracked_priority(READ_ONCE(task->tgid)) >= 0) {
		raw_spin_lock_irqsave(&hs_guard_lock, flags);
		hash_for_each_possible(hs_affinity_guards, record, guard_node,
				       (unsigned long)current) {
			if (record->guard_linked && record->task == current) {
				inherited_from_guard = true;
				break;
			}
		}
		raw_spin_unlock_irqrestore(&hs_guard_lock, flags);
	}
	if (inherited_from_guard) {
		atomic64_inc(&thread_inherit_candidates);
		if (likely(hs_sched_setaffinity_fn))
			ret = hs_sched_setaffinity_fn(READ_ONCE(task->pid),
						      cpu_possible_mask);
		if (ret) {
			atomic64_inc(&thread_inherit_fallbacks);
			ret = set_cpus_allowed_ptr(task, cpu_active_mask);
		}
		if (ret)
			atomic64_inc(&thread_inherit_errors);
		else
			atomic64_inc(&thread_inherit_resets);
	}
}

static void hs_task_rename_probe(void *unused, struct task_struct *task,
				 const char *comm)
{
	hs_queue_thread_event(task, comm);
}

static int hs_set_thread_traces_locked(bool enable)
{
	int ret;

	if (enable == thread_traces_active)
		return 0;
	if (enable) {
		ret = register_trace_task_newtask(hs_task_newtask_probe, NULL);
		if (ret)
			return ret;
		ret = register_trace_task_rename(hs_task_rename_probe, NULL);
		if (ret) {
			unregister_trace_task_newtask(hs_task_newtask_probe, NULL);
			tracepoint_synchronize_unregister();
			return ret;
		}
		WRITE_ONCE(thread_traces_active, true);
		return 0;
	}

	WRITE_ONCE(thread_traces_active, false);
	unregister_trace_task_rename(hs_task_rename_probe, NULL);
	unregister_trace_task_newtask(hs_task_newtask_probe, NULL);
	tracepoint_synchronize_unregister();
	return 0;
}

/*
 * Keep Mamba's critical-thread placement from being widened again by a
 * userspace vendor daemon after it has been applied.  This hook sits on the
 * sched_setaffinity syscall path and returns success without changing the
 * mask when a guarded task is given a different mask.  Internal cpuset and
 * kernel safety migrations do not use this hook and remain fully functional.
 *
 * A normal (unrestricted) Android vendor hook is used deliberately: it can be
 * unregistered and synchronized before a record is freed or this module is
 * unloaded.  The hook exists only while at least one affinity record exists,
 * so there is no idle tracepoint branch after the game exits.
 */
static void hs_sched_setaffinity_guard(void *unused, struct task_struct *task,
				       const struct cpumask *new_mask,
				       bool *skip)
{
	struct hs_affinity_record *record;
	unsigned long flags;

	if (!task || !new_mask || !skip || !READ_ONCE(hs_guard_records))
		return;

	raw_spin_lock_irqsave(&hs_guard_lock, flags);
	hash_for_each_possible(hs_affinity_guards, record, guard_node,
			       (unsigned long)task) {
		if (!record->guard_linked || record->task != task)
			continue;
		atomic64_inc(&hs_guard_events);
		if (!cpumask_equal(new_mask, &record->applied_mask)) {
			/* sched_setaffinity() returns its initialized success value. */
			WRITE_ONCE(*skip, true);
			atomic64_inc(&hs_guard_rejects);
		} else {
			atomic64_inc(&hs_guard_matches);
		}
		break;
	}
	raw_spin_unlock_irqrestore(&hs_guard_lock, flags);
}

static int hs_set_affinity_guard_hook_locked(bool enable)
{
	int ret;

	if (enable == hs_guard_hook_active)
		return 0;
	if (enable) {
		ret = register_trace_android_vh_sched_setaffinity_early(
			hs_sched_setaffinity_guard, NULL);
		if (ret) {
			hs_guard_register_errors++;
			return ret;
		}
		WRITE_ONCE(hs_guard_hook_active, true);
		return 0;
	}

	WRITE_ONCE(hs_guard_hook_active, false);
	unregister_trace_android_vh_sched_setaffinity_early(
		hs_sched_setaffinity_guard, NULL);
	tracepoint_synchronize_unregister();
	return 0;
}

static int hs_guard_add_locked(struct hs_affinity_record *record)
{
	unsigned long flags;
	int ret;

	if (record->guard_linked)
		return 0;
	if (!hs_guard_hook_active) {
		ret = hs_set_affinity_guard_hook_locked(true);
		if (ret)
			return ret;
	}

	raw_spin_lock_irqsave(&hs_guard_lock, flags);
	if (!record->guard_linked) {
		hash_add(hs_affinity_guards, &record->guard_node,
			 (unsigned long)record->task);
		record->guard_linked = true;
		WRITE_ONCE(hs_guard_records, hs_guard_records + 1);
	}
	raw_spin_unlock_irqrestore(&hs_guard_lock, flags);
	return 0;
}

static void hs_guard_remove_locked(struct hs_affinity_record *record)
{
	unsigned long flags;
	bool disable = false;

	if (!record->guard_linked)
		return;
	raw_spin_lock_irqsave(&hs_guard_lock, flags);
	if (record->guard_linked) {
		hash_del(&record->guard_node);
		record->guard_linked = false;
		if (WARN_ON_ONCE(!hs_guard_records))
			WRITE_ONCE(hs_guard_records, 0);
		else
			WRITE_ONCE(hs_guard_records, hs_guard_records - 1);
		disable = !hs_guard_records;
	}
	raw_spin_unlock_irqrestore(&hs_guard_lock, flags);

	if (disable && hs_guard_hook_active)
		hs_set_affinity_guard_hook_locked(false);
}

static void hs_guard_set_mask_locked(struct hs_affinity_record *record,
				     const struct cpumask *mask)
{
	unsigned long flags;

	/* Serialize the mask word(s) with a concurrent trace callback. */
	raw_spin_lock_irqsave(&hs_guard_lock, flags);
	cpumask_copy(&record->applied_mask, mask);
	raw_spin_unlock_irqrestore(&hs_guard_lock, flags);
}

static struct task_struct *hs_find_get_task(pid_t tid)
{
	struct task_struct *task;

	rcu_read_lock();
	task = find_task_by_vpid(tid);
	if (task)
		get_task_struct(task);
	rcu_read_unlock();
	return task;
}

static struct hs_affinity_record *hs_find_affinity_record(pid_t tid)
{
	struct hs_affinity_record *record;

	list_for_each_entry(record, &hs_affinity_records, node)
		if (record->tid == tid)
			return record;
	return NULL;
}

/*
 * set_cpus_allowed_ptr() changes only the task's instantaneous cpus_mask.
 * Android's OomAdjuster subsequently moves whole processes between cpusets,
 * and cpuset_attach_task() is then allowed to replace that mask.  The normal
 * sched_setaffinity() path additionally installs task->user_cpus_ptr; core
 * scheduler code intersects every later cpuset vote with that saved mask.
 * This is the kernel's native, zero-polling way to preserve affinity across
 * top-app/foreground/background migrations.
 *
 * sched_setaffinity() is intentionally not exported to modules by GKI.  It is
 * nevertheless a stable core symbol on this exact 6.6.89 target, so resolve
 * its address once with an unload-safe temporary kprobe.  No kprobe remains
 * registered after init.  A resolver or LSM failure falls back to the old
 * direct apply and is exposed in /proc instead of preventing boot.
 */
static int hs_resolve_sched_setaffinity(void)
{
	struct kprobe probe = {
		.symbol_name = "sched_setaffinity",
	};
	int ret;

	ret = register_kprobe(&probe);
	if (ret) {
		hs_sched_setaffinity_resolve_error = ret;
		return ret;
	}
	hs_sched_setaffinity_fn = (hs_sched_setaffinity_t)probe.addr;
	unregister_kprobe(&probe);
	if (!hs_sched_setaffinity_fn) {
		hs_sched_setaffinity_resolve_error = -ENOENT;
		return -ENOENT;
	}
	hs_sched_setaffinity_resolve_error = 0;
	return 0;
}

static int hs_set_persistent_affinity(struct hs_affinity_record *record,
				      const struct cpumask *mask,
				      bool restoring)
{
	long ret = -EOPNOTSUPP;

	if (likely(hs_sched_setaffinity_fn)) {
		ret = hs_sched_setaffinity_fn(record->tid, mask);
		if (!ret) {
			hs_last_native_affinity_error = 0;
			if (restoring) {
				thread_native_restores++;
			} else {
				record->native_persistent = true;
				thread_native_applies++;
			}
			return 0;
		}
		hs_last_native_affinity_error = ret;
		if (!restoring)
			record->native_persistent = false;
		/* Once a native apply has installed user_cpus_ptr, the old direct
		 * API cannot widen it during restore.  Keep the record for a later
		 * native retry instead of reporting a false successful restore. */
		if (restoring)
			return ret;
	}

	/* Preserve rc2 behavior as a boot-safe fallback.  It is not persistent
	 * across cpuset migration, which is why every use is counted visibly. */
	if (!restoring)
		record->native_persistent = false;
	thread_native_fallbacks++;
	return set_cpus_allowed_ptr(record->task, mask);
}

static void hs_release_affinity_record(struct hs_affinity_record *record)
{
	hs_guard_remove_locked(record);
	list_del(&record->node);
	put_task_struct(record->task);
	kfree(record);
}

static void hs_prune_dead_affinity_records_locked(void)
{
	struct hs_affinity_record *record, *next;

	list_for_each_entry_safe(record, next, &hs_affinity_records, node) {
		if (pid_alive(record->task))
			continue;
		hs_release_affinity_record(record);
		atomic64_inc(&thread_dead_pruned);
	}
}

static int hs_restore_record(struct hs_affinity_record *record)
{
	int ret = 0;

	/* The restore itself must not be mistaken for an external rewrite.
	 * Use an unrestricted user mask instead of replaying task->cpus_mask from
	 * the instant of first capture: that instantaneous mask may already be an
	 * OomAdjuster cpuset intersection or a temporary MiRim 0x38 boost.  An
	 * all-possible user mask removes Mamba's effective constraint and lets the
	 * task's current Android cpuset decide its actual runnable CPUs. */
	hs_guard_remove_locked(record);
	if (pid_alive(record->task))
		ret = hs_set_persistent_affinity(record, cpu_possible_mask,
						 true);
	if (ret) {
		thread_restore_errors++;
		/* Keep guarding a still-live task until a later restore succeeds. */
		hs_guard_add_locked(record);
		return ret;
	}
	thread_restore_changes++;
	return 0;
}

static void hs_restore_untracked_locked(const struct hs_tracked_process *set,
					int count)
{
	struct hs_affinity_record *record, *next;

	list_for_each_entry_safe(record, next, &hs_affinity_records, node) {
		if (hs_tgid_in_set(record->tgid, set, count))
			continue;
		/* Keep a live record after a transient restore failure.  A later
		 * replace/unload can retry it instead of forgetting the original
		 * mask and leaving the task pinned indefinitely. */
		if (!hs_restore_record(record))
			hs_release_affinity_record(record);
	}
}

static void hs_restore_all_locked(void)
{
	struct hs_affinity_record *record, *next;

	list_for_each_entry_safe(record, next, &hs_affinity_records, node) {
		hs_restore_record(record);
		hs_release_affinity_record(record);
	}
}

static int hs_replace_tracked_locked(const struct hs_tracked_process *next,
				     int count)
{
	int i;
	int ret;

	if (count > 0 && !thread_traces_active) {
		ret = hs_set_thread_traces_locked(true);
		if (ret)
			return ret;
	}
	for (i = 0; i < HS_MAX_TRACKED; i++) {
		WRITE_ONCE(tracked[i].tgid, i < count ? next[i].tgid : 0);
		WRITE_ONCE(tracked[i].priority,
			i < count ? next[i].priority : 0);
	}
	WRITE_ONCE(tracked_count, count);
	hs_restore_untracked_locked(next, count);
	if (!count && thread_traces_active)
		hs_set_thread_traces_locked(false);
	return 0;
}

static int hs_apply_thread_mask_locked(pid_t tgid, pid_t tid,
				       u64 start_boottime,
				       unsigned int mask, const char *role)
{
	struct hs_affinity_record *record;
	struct task_struct *task;
	cpumask_t target;
	cpumask_t previous;
	bool created = false;
	bool previous_native_persistent = false;
	int cpu;
	int ret;

	thread_apply_requests++;
	if (hs_tracked_priority(tgid) < 0 || !mask || (mask & ~0xffU))
		return -EINVAL;
	task = hs_find_get_task(tid);
	if (!task)
		return -ESRCH;
	if (READ_ONCE(task->tgid) != tgid) {
		put_task_struct(task);
		return -ESRCH;
	}
	if (start_boottime &&
	    READ_ONCE(task->start_boottime) != start_boottime) {
		put_task_struct(task);
		return -ESTALE;
	}
	cpumask_clear(&target);
	for (cpu = 0; cpu < 8; cpu++)
		if (mask & BIT(cpu))
			cpumask_set_cpu(cpu, &target);
	cpumask_and(&target, &target, cpu_active_mask);
	if (cpumask_empty(&target)) {
		put_task_struct(task);
		return -EINVAL;
	}

	record = hs_find_affinity_record(tid);
	if (record && record->task != task) {
		hs_release_affinity_record(record);
		record = NULL;
	}
	if (!record) {
		record = kzalloc(sizeof(*record), GFP_KERNEL);
		if (!record) {
			put_task_struct(task);
			return -ENOMEM;
		}
		record->task = task;
		record->tgid = tgid;
		record->tid = tid;
		INIT_LIST_HEAD(&record->node);
		INIT_HLIST_NODE(&record->guard_node);
		list_add_tail(&record->node, &hs_affinity_records);
		created = true;
	} else {
		put_task_struct(task);
	}
	if (cpumask_equal(&record->applied_mask, &target) &&
	    (record->native_persistent || !hs_sched_setaffinity_fn)) {
		ret = hs_guard_add_locked(record);
		if (ret) {
			thread_apply_errors++;
			return ret;
		}
		thread_apply_skips++;
		return 0;
	}
	cpumask_copy(&previous, &record->applied_mask);
	previous_native_persistent = record->native_persistent;
	record->native_persistent = false;
	/* Publish the exact target to the guard before the direct kernel apply,
	 * closing the window in which a vendor userspace vote could widen it. */
	hs_guard_set_mask_locked(record, &target);
	ret = hs_guard_add_locked(record);
	if (ret)
		goto rollback;
	ret = hs_set_persistent_affinity(record, &target, false);
	if (ret)
		goto rollback;
	strscpy(record->role, role && role[0] ? role : "mamba",
		sizeof(record->role));
	thread_apply_changes++;
	return 0;

rollback:
	thread_apply_errors++;
	if (created) {
		hs_release_affinity_record(record);
	} else {
		hs_guard_set_mask_locked(record, &previous);
		record->native_persistent = previous_native_persistent;
	}
	return ret;
}

static int hs_clear_thread_mask_locked(pid_t tgid, pid_t tid,
				       u64 start_boottime)
{
	struct hs_affinity_record *record;
	int ret;

	if (tgid <= 0 || tid <= 0)
		return -EINVAL;
	record = hs_find_affinity_record(tid);
	if (!record)
		return 0;
	if (record->tgid != tgid || READ_ONCE(record->task->tgid) != tgid)
		return -ESRCH;
	if (start_boottime &&
	    READ_ONCE(record->task->start_boottime) != start_boottime)
		return -ESTALE;

	ret = hs_restore_record(record);
	if (!ret)
		hs_release_affinity_record(record);
	return ret;
}

static int hs_parse_clear(char *input)
{
	unsigned long long start_boottime = 0;
	int tgid, tid;
	int fields;
	int ret;

	fields = sscanf(input, "clear tgid=%d tid=%d start=%llu",
			&tgid, &tid, &start_boottime);
	if (fields < 2)
		return -EINVAL;
	mutex_lock(&hs_thread_lock);
	ret = hs_clear_thread_mask_locked(tgid, tid, start_boottime);
	mutex_unlock(&hs_thread_lock);
	return ret;
}

static int hs_parse_replace(char *input)
{
	struct hs_tracked_process next[HS_MAX_TRACKED] = { };
	char *cursor = input;
	char *token;
	int declared = -1;
	int count = 0;
	int tgid, priority;
	int ret;

	token = strsep(&cursor, " \t\r\n");
	if (!token || strcmp(token, "replace"))
		return -EINVAL;
	while ((token = strsep(&cursor, " \t\r\n")) != NULL) {
		if (!token[0])
			continue;
		if (!strncmp(token, "count=", 6)) {
			if (kstrtoint(token + 6, 10, &declared))
				return -EINVAL;
			continue;
		}
		if (count >= HS_MAX_TRACKED ||
		    sscanf(token, "%d:%d", &tgid, &priority) != 2 ||
		    tgid <= 0 || priority < 0 || priority > 1)
			return -EINVAL;
		next[count].tgid = tgid;
		next[count].priority = priority;
		count++;
	}
	if (declared != count || count > HS_MAX_TRACKED)
		return -EINVAL;
	mutex_lock(&hs_thread_lock);
	ret = hs_replace_tracked_locked(next, count);
	mutex_unlock(&hs_thread_lock);
	return ret;
}

static int hs_parse_apply(char *input)
{
	char role[HS_ROLE_LEN] = "mamba";
	unsigned long long start_boottime = 0;
	unsigned int mask;
	int tgid, tid;
	int fields;
	int ret;

	fields = sscanf(input,
		"apply tgid=%d tid=%d start=%llu mask=%x role=%23s",
		&tgid, &tid, &start_boottime, &mask, role);
	if (fields < 4) {
		start_boottime = 0;
		fields = sscanf(input,
			"apply tgid=%d tid=%d mask=%x role=%23s",
			&tgid, &tid, &mask, role);
		if (fields < 3)
			return -EINVAL;
	}
	mutex_lock(&hs_thread_lock);
	ret = hs_apply_thread_mask_locked(tgid, tid, start_boottime,
					  mask, role);
	mutex_unlock(&hs_thread_lock);
	return ret;
}

static int hs_thread_status_show(struct seq_file *seq, void *unused)
{
	struct hs_affinity_record *record;
	int records = 0;
	int i;

	mutex_lock(&hs_thread_lock);
	seq_printf(seq, "ready=1 traces=%d tracked=%d\n",
		   thread_traces_active, tracked_count);
	for (i = 0; i < tracked_count; i++)
		seq_printf(seq, "slot%d tgid=%d priority=%d\n", i,
			   tracked[i].tgid, tracked[i].priority);
	list_for_each_entry(record, &hs_affinity_records, node)
		records++;
	seq_printf(seq,
		   "events=%lld drops=%lld uevent_errors=%lld records=%d\n",
		   atomic64_read(&thread_events),
		   atomic64_read(&thread_event_drops),
		   atomic64_read(&thread_uevent_errors), records);
	seq_printf(seq,
		   "apply requests=%lu changes=%lu skips=%lu errors=%lu restore=%lu restore_errors=%lu\n",
		   thread_apply_requests, thread_apply_changes,
		   thread_apply_skips, thread_apply_errors,
		   thread_restore_changes, thread_restore_errors);
	seq_printf(seq,
		   "guard active=%d records=%u events=%lld rejected=%lld same=%lld register_errors=%lu\n",
		   hs_guard_hook_active, hs_guard_records,
		   atomic64_read(&hs_guard_events),
		   atomic64_read(&hs_guard_rejects),
		   atomic64_read(&hs_guard_matches),
		   hs_guard_register_errors);
	seq_printf(seq,
		   "native_affinity resolved=%d applies=%lu restores=%lu fallbacks=%lu resolve_error=%d last_error=%d\n",
		   hs_sched_setaffinity_fn ? 1 : 0,
		   thread_native_applies, thread_native_restores,
		   thread_native_fallbacks,
		   hs_sched_setaffinity_resolve_error,
		   hs_last_native_affinity_error);
	seq_printf(seq,
		   "inherit_reset candidates=%lld resets=%lld fallbacks=%lld errors=%lld dead_pruned=%lld\n",
		   atomic64_read(&thread_inherit_candidates),
		   atomic64_read(&thread_inherit_resets),
		   atomic64_read(&thread_inherit_fallbacks),
		   atomic64_read(&thread_inherit_errors),
		   atomic64_read(&thread_dead_pruned));
	mutex_unlock(&hs_thread_lock);
	return 0;
}

static int hs_thread_status_open(struct inode *inode, struct file *file)
{
	return single_open(file, hs_thread_status_show, NULL);
}

static ssize_t hs_thread_control_write(struct file *file,
				       const char __user *buffer,
				       size_t count, loff_t *position)
{
	char input[HS_THREAD_INPUT_LEN];
	int ret;

	if (!count || count >= sizeof(input))
		return -EINVAL;
	if (copy_from_user(input, buffer, count))
		return -EFAULT;
	input[count] = '\0';
	if (!strncmp(input, "replace", 7))
		ret = hs_parse_replace(input);
	else if (!strncmp(input, "apply", 5))
		ret = hs_parse_apply(input);
	else if (!strncmp(input, "clear", 5))
		ret = hs_parse_clear(input);
	else
		ret = -EINVAL;
	return ret ? ret : count;
}

static const struct proc_ops hs_thread_status_ops = {
	.proc_open = hs_thread_status_open,
	.proc_read = seq_read,
	.proc_write = hs_thread_control_write,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static void hs_update_thermal_state_locked(int temp)
{
	if (temp < 0)
		return;

	switch (thermal_state) {
	case HS_THERMAL_FPS60:
		if (temp >= fps60_exit)
			return;
		thermal_state = temp >= hot_exit ? HS_THERMAL_HOT :
			temp >= warm_exit ? HS_THERMAL_WARM : HS_THERMAL_CLEAR;
		return;
	case HS_THERMAL_HOT:
		if (temp >= fps60_enter) {
			thermal_state = HS_THERMAL_FPS60;
			return;
		}
		if (temp >= hot_exit)
			return;
		thermal_state = temp >= warm_exit ? HS_THERMAL_WARM :
			HS_THERMAL_CLEAR;
		return;
	case HS_THERMAL_WARM:
		if (temp >= fps60_enter)
			thermal_state = HS_THERMAL_FPS60;
		else if (temp >= hot_enter)
			thermal_state = HS_THERMAL_HOT;
		else if (temp < warm_exit)
			thermal_state = HS_THERMAL_CLEAR;
		return;
	default:
		if (temp >= fps60_enter)
			thermal_state = HS_THERMAL_FPS60;
		else if (temp >= hot_enter)
			thermal_state = HS_THERMAL_HOT;
		else if (temp >= warm_enter)
			thermal_state = HS_THERMAL_WARM;
		return;
	}
}

static void hs_read_battery_temp_locked(void)
{
	struct power_supply *battery;
	union power_supply_propval value;

	battery = power_supply_get_by_name("battery");
	if (!battery)
		return;
	if (!power_supply_get_property(battery, POWER_SUPPLY_PROP_TEMP, &value))
		battery_temp_decic = value.intval;
	power_supply_put(battery);
	hs_update_thermal_state_locked(battery_temp_decic);
}

static void hs_apply_cluster(struct hs_cluster *cluster, unsigned int min,
			     unsigned int max)
{
	int ret;

	if (!cluster->policy)
		return;

	min = clamp(min, cluster->policy->cpuinfo.min_freq,
		    cluster->policy->cpuinfo.max_freq);
	max = clamp(max, cluster->policy->cpuinfo.min_freq,
		    cluster->policy->cpuinfo.max_freq);
	if (min > max)
		min = max;

	if (cluster->applied_min != min) {
		ret = freq_qos_update_request(&cluster->min_req, min);
		if (ret < 0)
			last_qos_error = ret;
		else
			cluster->applied_min = min;
	}
	if (cluster->applied_max != max) {
		ret = freq_qos_update_request(&cluster->max_req, max);
		if (ret < 0)
			last_qos_error = ret;
		else
			cluster->applied_max = max;
	}
}

static void hs_recalculate_locked(void)
{
	int mode = requested_mode < 0 ? daily_mode : requested_mode;
	int profile = requested_mode < 0 ? HS_PROFILE_APP : requested_profile;
	unsigned int min0, max0, min6, max6;

	mode = hs_clamp_mode(mode);
	profile = profile == HS_PROFILE_GAME ? HS_PROFILE_GAME : HS_PROFILE_APP;

	/* Safety states are caps.  A game/performance floor can never lift them. */
	if (saver_state || extreme_state) {
		mode = HS_POWERSAVE;
		profile = HS_PROFILE_APP;
	} else if (thermal_state >= HS_THERMAL_HOT) {
		mode = HS_POWERSAVE;
		profile = HS_PROFILE_APP;
	} else if (thermal_state >= HS_THERMAL_WARM) {
		mode = min(mode, (int)HS_BALANCE);
	}

	if (!screen_active) {
		profile = HS_PROFILE_APP;
		min0 = inactive_cpu0_min[mode];
		max0 = inactive_cpu0_max[mode];
		min6 = inactive_cpu6_min[mode];
		max6 = inactive_cpu6_max[mode];
	} else if (profile == HS_PROFILE_GAME) {
		min0 = game_cpu0_min[mode];
		max0 = game_cpu0_max[mode];
		min6 = game_cpu6_min[mode];
		max6 = game_cpu6_max[mode];
	} else {
		min0 = app_cpu0_min[mode];
		max0 = app_cpu0_max[mode];
		min6 = app_cpu6_min[mode];
		max6 = app_cpu6_max[mode];
	}
	if (extreme_state) {
		max0 = min(max0, extreme_max[0]);
		max6 = min(max6, extreme_max[1]);
	}
	/* FAS is a game-only upper-bound vote.  It can reduce the selected
	 * profile but can never lift a mode, thermal or saver ceiling. */
	if (screen_active && profile == HS_PROFILE_GAME && fas_active) {
		if (fas_cpu0_max)
			max0 = min(max0, fas_cpu0_max);
		if (fas_cpu6_max)
			max6 = min(max6, fas_cpu6_max);
	}

	effective_mode = mode;
	effective_profile = profile;
	fps_cap = thermal_state >= HS_THERMAL_FPS60 ? 60 : 0;
	hs_apply_cluster(&clusters[0], min0, max0);
	hs_apply_cluster(&clusters[1], min6, max6);
}

static void hs_apply_workfn(struct work_struct *work)
{
	bool announce;
	char mode_env[32];
	char profile_env[32];
	char thermal_env[32];
	char fps_env[32];
	char extreme_env[32];
	char *envp[] = {
		"HYPER_SCHED=1", mode_env, profile_env, thermal_env,
		fps_env, extreme_env, NULL
	};

	mutex_lock(&hs_lock);
	hs_read_battery_temp_locked();
	hs_recalculate_locked();
	apply_runs++;
	announce = effective_mode != announced_effective_mode ||
		effective_profile != announced_effective_profile ||
		thermal_state != announced_thermal_state ||
		fps_cap != announced_fps_cap ||
		extreme_state != announced_extreme_state;
	if (announce) {
		announced_effective_mode = effective_mode;
		announced_effective_profile = effective_profile;
		announced_thermal_state = thermal_state;
		announced_fps_cap = fps_cap;
		announced_extreme_state = extreme_state;
	}
	mutex_unlock(&hs_lock);

	if (!announce)
		return;
	scnprintf(mode_env, sizeof(mode_env), "EFFECTIVE_MODE=%d",
		  READ_ONCE(effective_mode));
	scnprintf(profile_env, sizeof(profile_env), "EFFECTIVE_PROFILE=%d",
		  READ_ONCE(effective_profile));
	scnprintf(thermal_env, sizeof(thermal_env), "THERMAL_STATE=%d",
		  READ_ONCE(thermal_state));
	scnprintf(fps_env, sizeof(fps_env), "FPS_CAP=%d", READ_ONCE(fps_cap));
	scnprintf(extreme_env, sizeof(extreme_env), "EXTREME_STATE=%d",
		  READ_ONCE(extreme_state));
	kobject_uevent_env(&THIS_MODULE->mkobj.kobj, KOBJ_CHANGE, envp);
}

static void hs_queue_apply(void)
{
	if (READ_ONCE(hs_ready))
		schedule_work(&apply_work);
}

static int hs_param_set_int_apply(const char *val, const struct kernel_param *kp)
{
	int ret = param_set_int(val, kp);

	if (!ret)
		hs_queue_apply();
	return ret;
}

static const struct kernel_param_ops hs_int_ops = {
	.set = hs_param_set_int_apply,
	.get = param_get_int,
};

module_param_cb(requested_mode, &hs_int_ops, &requested_mode, 0644);
module_param_cb(requested_profile, &hs_int_ops, &requested_profile, 0644);
module_param_cb(daily_mode, &hs_int_ops, &daily_mode, 0644);
module_param_cb(saver_state, &hs_int_ops, &saver_state, 0644);
module_param_cb(extreme_state, &hs_int_ops, &extreme_state, 0644);
module_param_cb(screen_active, &hs_int_ops, &screen_active, 0644);
module_param(warm_enter, int, 0444);
module_param(warm_exit, int, 0444);
module_param(hot_enter, int, 0444);
module_param(hot_exit, int, 0444);
module_param(fps60_enter, int, 0444);
module_param(fps60_exit, int, 0444);

static int hs_owner_set(const char *val, const struct kernel_param *kp)
{
	size_t len = strcspn(val, "\r\n");

	if (!len || len >= sizeof(owner))
		return -EINVAL;
	mutex_lock(&hs_lock);
	memcpy(owner, val, len);
	owner[len] = '\0';
	mutex_unlock(&hs_lock);
	return 0;
}

static int hs_owner_get(char *buffer, const struct kernel_param *kp)
{
	return scnprintf(buffer, PAGE_SIZE, "%s\n", owner);
}

static const struct kernel_param_ops hs_owner_ops = {
	.set = hs_owner_set,
	.get = hs_owner_get,
};

module_param_cb(owner, &hs_owner_ops, NULL, 0644);

static int hs_power_event(struct notifier_block *nb, unsigned long event,
			  void *data)
{
	struct power_supply *supply = data;

	/* USB-PD/PPS supplies may emit dense voltage/current edges.  Thermal
	 * policy only needs battery property changes, so discard the rest before
	 * touching the workqueue. */
	if (event == PSY_EVENT_PROP_CHANGED && supply && supply->desc &&
	    supply->desc->name && !strcmp(supply->desc->name, "battery")) {
		unsigned long now = jiffies;

		/* Charging drivers can report several properties as separate edges.
		 * Temperature changes slowly; cap those bursts to one apply per second
		 * without creating a periodic timer. */
		if (time_before(now, READ_ONCE(last_power_queue_jiffies) + HZ)) {
			power_coalesced++;
			return NOTIFY_OK;
		}
		WRITE_ONCE(last_power_queue_jiffies, now);
		power_events++;
		hs_queue_apply();
	}
	return NOTIFY_OK;
}

static int hs_status_show(struct seq_file *seq, void *unused)
{
	mutex_lock(&hs_lock);
	seq_printf(seq,
		   "ready=%d request=%d profile=%d(%s) daily=%d effective=%d(%s) effective_profile=%d(%s)\n",
		   hs_ready, requested_mode, requested_profile,
		   hs_profile_name(requested_profile), daily_mode, effective_mode,
		   hs_mode_name(effective_mode), effective_profile,
		   hs_profile_name(effective_profile));
	seq_printf(seq,
		   "saver=%d extreme=%d active=%d temp_decic=%d thermal=%d fps_cap=%d\n",
		   saver_state, extreme_state, screen_active, battery_temp_decic,
		   thermal_state, fps_cap);
	seq_printf(seq, "owner=%s\n", owner);
	seq_printf(seq, "fas active=%d cpu0_max=%u cpu6_max=%u updates=%lu\n",
		   fas_active, fas_cpu0_max, fas_cpu6_max, fas_updates);
	seq_printf(seq,
		   "events control=%lu power=%lu coalesced=%lu apply=%lu qos_error=%d\n",
		   control_updates, power_events, power_coalesced,
		   apply_runs, last_qos_error);
	seq_printf(seq, "policy0 min=%u max=%u hw=%u-%u cpus=0-5\n",
		   clusters[0].applied_min, clusters[0].applied_max,
		   clusters[0].policy ? clusters[0].policy->cpuinfo.min_freq : 0,
		   clusters[0].policy ? clusters[0].policy->cpuinfo.max_freq : 0);
	seq_printf(seq, "policy6 min=%u max=%u hw=%u-%u cpus=6-7\n",
		   clusters[1].applied_min, clusters[1].applied_max,
		   clusters[1].policy ? clusters[1].policy->cpuinfo.min_freq : 0,
		   clusters[1].policy ? clusters[1].policy->cpuinfo.max_freq : 0);
	mutex_unlock(&hs_lock);
	return 0;
}

static int hs_status_open(struct inode *inode, struct file *file)
{
	return single_open(file, hs_status_show, NULL);
}

/*
 * One write updates the complete userspace vote.  This prevents observable
 * intermediate states such as a new game mode paired with the previous
 * saver/profile flag.
 */
static ssize_t hs_control_write(struct file *file, const char __user *buffer,
				 size_t count, loff_t *position)
{
	char input[HS_CONTROL_LEN];
	char next_owner[HS_OWNER_LEN];
	int request, profile, saver, extreme, active = 1;
	unsigned int fas0, fas6;
	int fields;
	size_t len;

	if (!count || count >= sizeof(input))
		return -EINVAL;
	if (copy_from_user(input, buffer, count))
		return -EFAULT;
	input[count] = '\0';
	fields = sscanf(input, "fas active=%d cpu0=%u cpu6=%u",
			&active, &fas0, &fas6);
	if (fields == 3) {
		if (active < 0 || active > 1 ||
		    (active && (fas0 < 384000 || fas0 > 5000000 ||
			       fas6 < 384000 || fas6 > 5000000)))
			return -EINVAL;
		mutex_lock(&hs_lock);
		fas_active = active;
		fas_cpu0_max = active ? fas0 : 0;
		fas_cpu6_max = active ? fas6 : 0;
		fas_updates++;
		mutex_unlock(&hs_lock);
		hs_queue_apply();
		return count;
	}

	fields = sscanf(input,
			"request=%d profile=%d saver=%d extreme=%d active=%d owner=%191s",
			&request, &profile, &saver, &extreme, &active, next_owner);
	if (fields != 6 || request < -1 || request >= HS_MODES ||
	    profile < HS_PROFILE_APP || profile > HS_PROFILE_GAME ||
	    saver < 0 || saver > 1 || extreme < 0 || extreme > 1 ||
	    active < 0 || active > 1)
		return -EINVAL;
	len = strnlen(next_owner, sizeof(next_owner));
	if (!len || len >= sizeof(next_owner))
		return -EINVAL;

	mutex_lock(&hs_lock);
	requested_mode = request;
	requested_profile = profile;
	saver_state = saver;
	extreme_state = extreme;
	screen_active = active;
	if (profile != HS_PROFILE_GAME || !active) {
		fas_active = false;
		fas_cpu0_max = 0;
		fas_cpu6_max = 0;
	}
	strscpy(owner, next_owner, sizeof(owner));
	control_updates++;
	mutex_unlock(&hs_lock);
	hs_queue_apply();
	return count;
}

static const struct proc_ops hs_status_ops = {
	.proc_open = hs_status_open,
	.proc_read = seq_read,
	.proc_write = hs_control_write,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static void hs_release_cluster(struct hs_cluster *cluster)
{
	if (cluster->max_active) {
		freq_qos_remove_request(&cluster->max_req);
		cluster->max_active = false;
	}
	if (cluster->min_active) {
		freq_qos_remove_request(&cluster->min_req);
		cluster->min_active = false;
	}
	if (cluster->policy) {
		cpufreq_cpu_put(cluster->policy);
		cluster->policy = NULL;
	}
}

static int hs_prepare_cluster(struct hs_cluster *cluster)
{
	int ret;

	cluster->policy = cpufreq_cpu_get(cluster->cpu);
	if (!cluster->policy)
		return -ENODEV;

	ret = freq_qos_add_request(&cluster->policy->constraints,
				   &cluster->min_req, FREQ_QOS_MIN,
				   FREQ_QOS_MIN_DEFAULT_VALUE);
	if (ret < 0)
		goto fail;
	cluster->min_active = true;

	ret = freq_qos_add_request(&cluster->policy->constraints,
				   &cluster->max_req, FREQ_QOS_MAX,
				   FREQ_QOS_MAX_DEFAULT_VALUE);
	if (ret < 0)
		goto fail;
	cluster->max_active = true;
	cluster->applied_min = UINT_MAX;
	cluster->applied_max = UINT_MAX;
	return 0;

fail:
	hs_release_cluster(cluster);
	return ret;
}

static int hs_validate_config(void)
{
	int mode;

	if (daily_mode < HS_POWERSAVE || daily_mode > HS_FAST)
		return -EINVAL;
	if (warm_exit >= warm_enter || hot_exit >= hot_enter ||
	    fps60_exit >= fps60_enter || warm_enter > hot_exit ||
	    hot_enter > fps60_exit)
		return -EINVAL;
	for (mode = 0; mode < HS_MODES; mode++) {
		if (app_cpu0_min[mode] > app_cpu0_max[mode] ||
		    app_cpu6_min[mode] > app_cpu6_max[mode] ||
		    game_cpu0_min[mode] > game_cpu0_max[mode] ||
		    game_cpu6_min[mode] > game_cpu6_max[mode] ||
		    inactive_cpu0_min[mode] > inactive_cpu0_max[mode] ||
		    inactive_cpu6_min[mode] > inactive_cpu6_max[mode])
			return -EINVAL;
	}
	if (!extreme_max[0] || !extreme_max[1])
		return -EINVAL;
	return 0;
}

static int __init hs_init(void)
{
	int ret;

	INIT_WORK(&apply_work, hs_apply_workfn);
	hs_thread_wq = alloc_ordered_workqueue("op13_scene_threads",
					 WQ_MEM_RECLAIM | WQ_FREEZABLE);
	if (!hs_thread_wq)
		return -ENOMEM;
	ret = hs_validate_config();
	if (ret) {
		pr_err("invalid frequency or thermal configuration\n");
		goto fail_validate;
	}
	ret = hs_resolve_sched_setaffinity();
	if (ret)
		pr_warn("native sched_setaffinity unavailable (%d); using non-persistent fallback\n",
			ret);
	ret = hs_prepare_cluster(&clusters[0]);
	if (ret)
		goto fail_validate;
	ret = hs_prepare_cluster(&clusters[1]);
	if (ret)
		goto fail_cluster1;

	status_entry = proc_create(HS_NAME, 0644, NULL, &hs_status_ops);
	if (!status_entry) {
		ret = -ENOMEM;
		goto fail_proc;
	}
	thread_entry = proc_create(HS_THREAD_NAME, 0644, NULL,
				   &hs_thread_status_ops);
	if (!thread_entry) {
		ret = -ENOMEM;
		goto fail_thread_proc;
	}

	power_nb.notifier_call = hs_power_event;
	ret = power_supply_reg_notifier(&power_nb);
	if (ret)
		goto fail_notifier;

	WRITE_ONCE(hs_ready, true);
	schedule_work(&apply_work);
	pr_info("loaded: OP13 Scene Scheduler atomic policy and idle-zero thread transport\n");
	return 0;

fail_notifier:
	proc_remove(thread_entry);
	thread_entry = NULL;
fail_thread_proc:
	proc_remove(status_entry);
	status_entry = NULL;
fail_proc:
	hs_release_cluster(&clusters[1]);
fail_cluster1:
	hs_release_cluster(&clusters[0]);
fail_validate:
	destroy_workqueue(hs_thread_wq);
	hs_thread_wq = NULL;
	return ret;
}

static void __exit hs_exit(void)
{
	WRITE_ONCE(hs_ready, false);
	power_supply_unreg_notifier(&power_nb);
	cancel_work_sync(&apply_work);
	mutex_lock(&hs_thread_lock);
	hs_set_thread_traces_locked(false);
	WRITE_ONCE(tracked_count, 0);
	mutex_unlock(&hs_thread_lock);
	flush_workqueue(hs_thread_wq);
	mutex_lock(&hs_thread_lock);
	hs_restore_all_locked();
	/* Defensive cleanup for a partially constructed record after an error. */
	if (hs_guard_hook_active)
		hs_set_affinity_guard_hook_locked(false);
	mutex_unlock(&hs_thread_lock);
	if (thread_entry)
		proc_remove(thread_entry);
	if (status_entry)
		proc_remove(status_entry);
	hs_release_cluster(&clusters[1]);
	hs_release_cluster(&clusters[0]);
	destroy_workqueue(hs_thread_wq);
	pr_info("unloaded and restored thread masks plus all qos requests\n");
}

module_init(hs_init);
module_exit(hs_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("MIO compatibility research");
MODULE_DESCRIPTION("OP13 Scene Scheduler atomic event-only SM8750 scheduler policy");
MODULE_VERSION("0.6.0-fas");
