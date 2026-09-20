#define _GNU_SOURCE

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define TEXT_CAP 32768
#define MAX_TRACKED 8

static unsigned long long sample_sequence;
static pid_t cached_adapter_pid = -1;

static long long monotonic_us(void)
{
	struct timespec value;

	if (clock_gettime(CLOCK_MONOTONIC, &value) != 0)
		return 0;
	return (long long)value.tv_sec * 1000000LL + value.tv_nsec / 1000LL;
}

static ssize_t read_text(const char *path, char *buffer, size_t capacity)
{
	ssize_t total = 0;
	int fd;

	if (!buffer || capacity < 2)
		return -1;
	fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		buffer[0] = '\0';
		return -1;
	}
	while ((size_t)total < capacity - 1) {
		ssize_t count = read(fd, buffer + total, capacity - 1 - total);

		if (count > 0) {
			total += count;
			continue;
		}
		if (count < 0 && errno == EINTR)
			continue;
		break;
	}
	close(fd);
	buffer[total] = '\0';
	return total;
}

static char *trim(char *value)
{
	char *end;

	while (*value && isspace((unsigned char)*value))
		value++;
	end = value + strlen(value);
	while (end > value && isspace((unsigned char)end[-1]))
		*--end = '\0';
	return value;
}

static void sanitize_field(char *value)
{
	for (; *value; value++) {
		unsigned char ch = (unsigned char)*value;

		if (ch == '|' || ch == '\r' || ch == '\n' || ch < 0x20)
			*value = '_';
	}
}

static void emit_prefixed_file(const char *path, const char *prefix)
{
	char *line = NULL;
	size_t size = 0;
	FILE *file = fopen(path, "re");

	if (!file)
		return;
	while (getline(&line, &size, file) >= 0) {
		size_t length = strlen(line);

		while (length && (line[length - 1] == '\n' ||
				  line[length - 1] == '\r'))
			line[--length] = '\0';
		printf("%s%s\n", prefix, line);
	}
	free(line);
	fclose(file);
}

static void emit_value(const char *key, const char *path)
{
	char buffer[4096];
	char *value;

	if (read_text(path, buffer, sizeof(buffer)) <= 0) {
		printf("%s=-1\n", key);
		return;
	}
	value = trim(buffer);
	if (!*value)
		value = "-1";
	else {
		char *newline = strpbrk(value, "\r\n");
		if (newline)
			*newline = '\0';
	}
	printf("%s=%s\n", key, value);
}

static void emit_cpu_policy(const char *key, const char *base)
{
	static const struct {
		const char *suffix;
		const char *field;
	} nodes[] = {
		{ "scaling_cur_freq", "cur" },
		{ "scaling_min_freq", "min" },
		{ "scaling_max_freq", "max" },
		{ "cpuinfo_min_freq", "hwmin" },
		{ "cpuinfo_max_freq", "hwmax" },
		{ "scaling_governor", "gov" },
	};
	char name[64];
	char path[256];
	size_t index;

	for (index = 0; index < sizeof(nodes) / sizeof(nodes[0]); index++) {
		snprintf(name, sizeof(name), "%s.%s", key, nodes[index].field);
		snprintf(path, sizeof(path), "%s/%s", base, nodes[index].suffix);
		emit_value(name, path);
	}
}

static void emit_gpu_busy(void)
{
	char buffer[256];
	double busy = -1.0;

	if (read_text("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
		      buffer, sizeof(buffer)) > 0) {
		char *cursor = buffer;

		while (*cursor && !isdigit((unsigned char)*cursor) && *cursor != '.')
			cursor++;
		if (*cursor)
			busy = strtod(cursor, NULL);
	}
	if (busy < 0.0 && read_text("/sys/class/kgsl/kgsl-3d0/gpubusy",
				    buffer, sizeof(buffer)) > 0) {
		unsigned long long active = 0, total = 0;

		if (sscanf(buffer, "%llu %llu", &active, &total) == 2 && total)
			busy = (double)active * 100.0 / (double)total;
	}
	printf("gpu.busy=%.1f\n", busy);
}

static void emit_system_ticks(void)
{
	char line[4096];
	unsigned long long total = 0;
	unsigned long long idle = 0;
	int cores = 0;
	FILE *file = fopen("/proc/stat", "re");

	if (!file) {
		printf("system.ticks=0\nsystem.idle_ticks=0\nsystem.cores=8\n");
		return;
	}
	while (fgets(line, sizeof(line), file)) {
		if (!strncmp(line, "cpu ", 4)) {
			char *save = NULL;
			char *token = strtok_r(line + 4, " \t\r\n", &save);
			int field = 0;

			while (token) {
				unsigned long long value = strtoull(token, NULL, 10);

				total += value;
				if (field == 3 || field == 4)
					idle += value;
				field++;
				token = strtok_r(NULL, " \t\r\n", &save);
			}
		} else if (!strncmp(line, "cpu", 3) &&
			   isdigit((unsigned char)line[3])) {
			cores++;
		}
	}
	fclose(file);
	printf("system.ticks=%llu\n", total);
	printf("system.idle_ticks=%llu\n", idle);
	printf("system.cores=%d\n", cores > 0 ? cores : 8);
}

static void emit_memory(void)
{
	char line[512];
	unsigned long total = 0, available = 0, swap_total = 0, swap_free = 0;
	FILE *file = fopen("/proc/meminfo", "re");

	if (file) {
		while (fgets(line, sizeof(line), file)) {
			if (sscanf(line, "MemTotal: %lu", &total) == 1)
				continue;
			if (sscanf(line, "MemAvailable: %lu", &available) == 1)
				continue;
			if (sscanf(line, "SwapTotal: %lu", &swap_total) == 1)
				continue;
			sscanf(line, "SwapFree: %lu", &swap_free);
		}
		fclose(file);
	}
	printf("memory.total_kb=%lu\n", total);
	printf("memory.available_kb=%lu\n", available);
	printf("memory.swap_total_kb=%lu\n", swap_total);
	printf("memory.swap_free_kb=%lu\n", swap_free);
}

static long long read_integer(const char *path, long long fallback)
{
	char buffer[256];
	char *value;
	char *end;
	long long result;

	if (read_text(path, buffer, sizeof(buffer)) <= 0)
		return fallback;
	value = trim(buffer);
	errno = 0;
	result = strtoll(value, &end, 10);
	return errno || end == value ? fallback : result;
}

static void emit_battery(void)
{
	static const char *temperature_nodes[] = {
		"/sys/class/power_supply/battery/temp",
		"/sys/class/power_supply/battery/batt_temp",
	};
	long long current = read_integer(
		"/sys/class/power_supply/battery/current_now", 0);
	long long voltage = read_integer(
		"/sys/class/power_supply/battery/voltage_now", 0);
	double power;
	long long pack_voltage;
	size_t index;
	char status[128];

	if (current < 0)
		current = -current;
	if (voltage < 0)
		voltage = -voltage;
	/* OPlus kernels commonly expose current_now in mA despite the standard
	 * power_supply ABI using uA.  Metric solves this with calibration; this
	 * device-focused collector applies the same 1000x normalization only to
	 * values too small to be a plausible uA reading. */
	if (current > 0 && current < 10000)
		current *= 1000;
	/* OnePlus 13 reports one cell's voltage and the series-pack current. */
	pack_voltage = voltage > 0 ? voltage * 2 : 0;
	power = (double)current * (double)pack_voltage / 1000000000000.0;
	printf("battery.current_ua=%lld\n", current);
	printf("battery.voltage_uv=%lld\n", voltage);
	printf("battery.pack_voltage_uv=%lld\n", pack_voltage);
	printf("battery.cell_count=2\n");
	emit_value("battery.charge_counter_uah",
		   "/sys/class/power_supply/battery/charge_counter");
	emit_value("power.usb_online", "/sys/class/power_supply/usb/online");
	printf("battery.power_w=%.6f\n", power);
	emit_value("battery.capacity",
		   "/sys/class/power_supply/battery/capacity");
	if (read_text("/sys/class/power_supply/battery/status",
		      status, sizeof(status)) > 0) {
		char *value = trim(status);
		char *newline = strpbrk(value, "\r\n");

		if (newline)
			*newline = '\0';
		sanitize_field(value);
		printf("battery.status=%s\n", value);
	} else {
		printf("battery.status=Unknown\n");
	}
	for (index = 0; index < sizeof(temperature_nodes) /
				      sizeof(temperature_nodes[0]); index++) {
		if (access(temperature_nodes[index], R_OK) == 0) {
			emit_value("battery.temp_decic", temperature_nodes[index]);
			return;
		}
	}
	printf("battery.temp_decic=-1\n");
}

static void emit_display_fps(void)
{
	static const char *nodes[] = {
		"/sys/class/drm/sde-crtc-0/measured_fps",
		"/sys/class/graphics/fb0/measured_fps",
		("/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/"
		 "card0-sde-crtc-0/measured_fps"),
	};
	char buffer[256];
	size_t index;

	for (index = 0; index < sizeof(nodes) / sizeof(nodes[0]); index++) {
		char *cursor;

		if (read_text(nodes[index], buffer, sizeof(buffer)) <= 0)
			continue;
		cursor = buffer;
		while (*cursor && !isdigit((unsigned char)*cursor) && *cursor != '.')
			cursor++;
		if (*cursor) {
			printf("display.fps=%.3f\n", strtod(cursor, NULL));
			return;
		}
	}
	printf("display.fps=-1\n");
}

static bool adapter_name_matches(pid_t pid)
{
	char path[96];
	char value[512];

	snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
	if (read_text(path, value, sizeof(value)) > 0 &&
	    (!strcmp(value, "op13_scene_sched") ||
	     !strcmp(value, "op13_scene_sched")))
		return true;
	snprintf(path, sizeof(path), "/proc/%d/comm", pid);
	if (read_text(path, value, sizeof(value)) > 0) {
		char *name = trim(value);

		if (!strncmp(name, "op13_scene_", 12) ||
		    !strncmp(name, "op13_scene_", 12))
			return true;
	}
	return false;
}

static pid_t find_adapter_pid(void)
{
	struct dirent *entry;
	DIR *directory;

	if (cached_adapter_pid > 0 && adapter_name_matches(cached_adapter_pid))
		return cached_adapter_pid;
	cached_adapter_pid = -1;
	directory = opendir("/proc");
	if (!directory)
		return -1;
	while ((entry = readdir(directory))) {
		char *end;
		long value;

		if (!isdigit((unsigned char)entry->d_name[0]))
			continue;
		value = strtol(entry->d_name, &end, 10);
		if (*end || value <= 0 || value > 1 << 30)
			continue;
		if (adapter_name_matches((pid_t)value)) {
			cached_adapter_pid = (pid_t)value;
			break;
		}
	}
	closedir(directory);
	return cached_adapter_pid;
}

static unsigned long long process_ticks(pid_t pid)
{
	char path[96];
	char buffer[4096];
	char *right;
	char *save = NULL;
	char *token;
	int field = 3;
	unsigned long long user = 0, system = 0;

	snprintf(path, sizeof(path), "/proc/%d/stat", pid);
	if (read_text(path, buffer, sizeof(buffer)) <= 0)
		return 0;
	right = strrchr(buffer, ')');
	if (!right || right[1] != ' ')
		return 0;
	token = strtok_r(right + 2, " ", &save);
	while (token) {
		if (field == 14)
			user = strtoull(token, NULL, 10);
		else if (field == 15) {
			system = strtoull(token, NULL, 10);
			break;
		}
		field++;
		token = strtok_r(NULL, " ", &save);
	}
	return user + system;
}

static unsigned long process_rss_kb(pid_t pid)
{
	char path[96];
	char line[512];
	unsigned long value = 0;
	FILE *file;

	snprintf(path, sizeof(path), "/proc/%d/status", pid);
	file = fopen(path, "re");
	if (!file)
		return 0;
	while (fgets(line, sizeof(line), file)) {
		if (sscanf(line, "VmRSS: %lu", &value) == 1)
			break;
	}
	fclose(file);
	return value;
}

static void emit_adapter(void)
{
	pid_t pid = find_adapter_pid();

	printf("adapter.pid=%d\n", pid > 0 ? pid : -1);
	printf("adapter.ticks=%llu\n", pid > 0 ? process_ticks(pid) : 0);
	printf("adapter.rss_kb=%lu\n", pid > 0 ? process_rss_kb(pid) : 0);
}

static int collect_tracked_tgids(pid_t *tgids, int capacity)
{
	char line[512];
	int count = 0;
	FILE *file = fopen("/proc/op13_scene_threads", "re");

	if (!file)
		return 0;
	while (count < capacity && fgets(line, sizeof(line), file)) {
		int tgid = -1;

		if (sscanf(line, "slot%*d tgid=%d", &tgid) == 1 && tgid > 0)
			tgids[count++] = (pid_t)tgid;
	}
	fclose(file);
	return count;
}

static void read_package_name(pid_t tgid, char *output, size_t capacity)
{
	char path[96];

	snprintf(path, sizeof(path), "/proc/%d/cmdline", tgid);
	if (read_text(path, output, capacity) <= 0)
		snprintf(output, capacity, "%d", tgid);
	sanitize_field(output);
}

static void read_allowed_list(pid_t tgid, pid_t tid,
			      char *output, size_t capacity)
{
	char path[128];
	char line[512];
	FILE *file;

	output[0] = '\0';
	snprintf(path, sizeof(path), "/proc/%d/task/%d/status", tgid, tid);
	file = fopen(path, "re");
	if (!file)
		return;
	while (fgets(line, sizeof(line), file)) {
		if (!strncmp(line, "Cpus_allowed_list:", 18)) {
			snprintf(output, capacity, "%s", trim(line + 18));
			break;
		}
	}
	fclose(file);
}

static void emit_tracked_tasks(pid_t tgid)
{
	char task_path[96];
	char package_name[512];
	struct dirent *entry;
	DIR *directory;

	read_package_name(tgid, package_name, sizeof(package_name));
	snprintf(task_path, sizeof(task_path), "/proc/%d/task", tgid);
	directory = opendir(task_path);
	if (!directory)
		return;
	while ((entry = readdir(directory))) {
		char comm_path[160];
		char comm[256];
		char allowed[128];
		char *end;
		long value;

		if (!isdigit((unsigned char)entry->d_name[0]))
			continue;
		value = strtol(entry->d_name, &end, 10);
		if (*end || value <= 0 || value > 1 << 30)
			continue;
		snprintf(comm_path, sizeof(comm_path), "/proc/%d/task/%ld/comm",
			 tgid, value);
		if (read_text(comm_path, comm, sizeof(comm)) <= 0)
			continue;
		snprintf(comm, sizeof(comm), "%s", trim(comm));
		sanitize_field(comm);
		read_allowed_list(tgid, (pid_t)value, allowed, sizeof(allowed));
		sanitize_field(allowed);
		printf("task|%d|%ld|%s|%s|%s\n", tgid, value,
		       package_name, comm, allowed);
	}
	closedir(directory);
}

static pid_t find_main_process(void)
{
	char state[4096];
	char package_name[512] = "";
	char *line;
	char *save;
	DIR *directory;
	struct dirent *entry;

	if (read_text("/data/adb/op13_hyperos_fix/sched/visible_apps.state",
		      state, sizeof(state)) <= 0)
		return -1;
	for (line = strtok_r(state, "\r\n", &save); line;
	     line = strtok_r(NULL, "\r\n", &save)) {
		if (!strncmp(line, "main=", 5)) {
			snprintf(package_name, sizeof(package_name), "%s", line + 5);
			break;
		}
	}
	if (!strchr(package_name, '.'))
		return -1;
	directory = opendir("/proc");
	if (!directory)
		return -1;
	while ((entry = readdir(directory))) {
		char path[96];
		char cmdline[512];
		char *end;
		long pid;

		if (!isdigit((unsigned char)entry->d_name[0]))
			continue;
		pid = strtol(entry->d_name, &end, 10);
		if (*end || pid <= 0 || pid > 1 << 30)
			continue;
		snprintf(path, sizeof(path), "/proc/%ld/cmdline", pid);
		if (read_text(path, cmdline, sizeof(cmdline)) <= 0)
			continue;
		if (!strcmp(cmdline, package_name)) {
			closedir(directory);
			return (pid_t)pid;
		}
	}
	closedir(directory);
	return -1;
}

static void emit_full_details(void)
{
	pid_t tgids[MAX_TRACKED];
	int count;
	int index;

	emit_prefixed_file("/data/adb/op13_hyperos_fix/sched/thread.json",
			   "rules|");
	count = collect_tracked_tgids(tgids, MAX_TRACKED);
	for (index = 0; index < count; index++)
		emit_tracked_tasks(tgids[index]);
	{
		pid_t main_pid = find_main_process();
		bool already_emitted = false;

		for (index = 0; index < count; index++)
			if (tgids[index] == main_pid)
				already_emitted = true;
		if (main_pid > 0 && !already_emitted)
			emit_tracked_tasks(main_pid);
	}
}

static void emit_process_list(void)
{
	DIR *directory = opendir("/proc");
	struct dirent *entry;
	int emitted = 0;

	if (!directory)
		return;
	while (emitted < 384 && (entry = readdir(directory))) {
		char path[96];
		char name[512];
		char *end;
		long pid;
		unsigned long long ticks;
		unsigned long rss;

		if (!isdigit((unsigned char)entry->d_name[0]))
			continue;
		pid = strtol(entry->d_name, &end, 10);
		if (*end || pid <= 0 || pid > 1 << 30)
			continue;
		snprintf(path, sizeof(path), "/proc/%ld/cmdline", pid);
		if (read_text(path, name, sizeof(name)) <= 0 || !name[0])
			continue;
		if (!strchr(name, '.') && strncmp(name, "system", 6))
			continue;
		sanitize_field(name);
		ticks = process_ticks((pid_t)pid);
		rss = process_rss_kb((pid_t)pid);
		printf("process|%ld|%llu|%lu|%s\n", pid, ticks, rss, name);
		emitted++;
	}
	closedir(directory);
}

static void probe_sample(int detail)
{
	long long started = monotonic_us();

	puts("__MAMBA_PROBE_BEGIN__");
	printf("root.uid=%d\n", getuid());
	printf("sampler.backend=native\n");
	printf("sampler.sequence=%llu\n", ++sample_sequence);
	emit_prefixed_file("/proc/op13_scene_sched", "sched|");
	emit_prefixed_file("/proc/op13_scene_threads", "threads|");
	emit_prefixed_file("/data/adb/op13_hyperos_fix/sched/visible_apps.state",
			   "visible|");
	emit_cpu_policy("cpu0", "/sys/devices/system/cpu/cpufreq/policy0");
	emit_cpu_policy("cpu6", "/sys/devices/system/cpu/cpufreq/policy6");
	emit_value("gpu.cur", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq");
	emit_value("gpu.min", "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq");
	emit_value("gpu.max", "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq");
	emit_value("gpu.gov", "/sys/class/kgsl/kgsl-3d0/devfreq/governor");
	emit_gpu_busy();
	emit_system_ticks();
	emit_memory();
	emit_battery();
	emit_display_fps();
	emit_adapter();
	if (detail >= 1)
		emit_full_details();
	if (detail >= 2)
		emit_process_list();
	printf("sampler.elapsed_us=%lld\n", monotonic_us() - started);
	puts("__MAMBA_PROBE_END__");
	fflush(stdout);
}

static void probe_power(void)
{
	long long started = monotonic_us();

	puts("__MAMBA_PROBE_BEGIN__");
	printf("root.uid=%d\n", getuid());
	printf("sampler.backend=native-power\n");
	printf("sampler.sequence=%llu\n", ++sample_sequence);
	emit_prefixed_file("/data/adb/op13_hyperos_fix/sched/visible_apps.state",
			   "visible|");
	emit_battery();
	printf("sampler.elapsed_us=%lld\n", monotonic_us() - started);
	puts("__MAMBA_PROBE_END__");
	fflush(stdout);
}

int main(int argc, char **argv)
{
	char command[64];

	/* The short su launcher copies us to /data/local/tmp.  Removing the name
	 * immediately leaves no persistent helper file while the mapped process
	 * continues to run normally. */
	if (argc > 1 && !strcmp(argv[1], "--unlink"))
		unlink(argv[0]);
	prctl(PR_SET_NAME, "op13_probe_io", 0, 0, 0);
	signal(SIGPIPE, SIG_IGN);
	setvbuf(stdout, NULL, _IOLBF, 0);
	puts("__MAMBA_PROBE_READY__");
	while (fgets(command, sizeof(command), stdin)) {
		char *value = trim(command);

		if (!strcmp(value, "SAMPLE"))
			probe_sample(0);
		else if (!strcmp(value, "FULL"))
			probe_sample(1);
		else if (!strcmp(value, "MONITOR"))
			probe_sample(2);
		else if (!strcmp(value, "POWER"))
			probe_power();
		else if (!strcmp(value, "EXIT"))
			break;
	}
	return 0;
}
