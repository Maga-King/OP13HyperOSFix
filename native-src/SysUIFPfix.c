#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define ACTION_DOWN "com.sysuifpfix.action.FINGERPRINT_DOWN"
#define ACTION_UP "com.sysuifpfix.action.FINGERPRINT_UP"
#define SYSTEMUI_PACKAGE "com.android.systemui"
#define KMSG_NODE "/dev/kmsg"
#define LOCK_FILE "/data/local/tmp/SysUIFPfix.lock"
#define UP_TIMEOUT_MS 600

extern char **environ;

static volatile sig_atomic_t g_stop;
static bool g_finger_down;

static void log_message(const char *message) {
    dprintf(STDERR_FILENO, "SysUIFPfix: %s\n", message);
}

static void handle_signal(int signal_number) {
    (void)signal_number;
    g_stop = 1;
}

static int64_t monotonic_ms(void) {
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return 0;
    }
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static bool send_fingerprint_event(bool down) {
    char *const arguments[] = {
        "/system/bin/am",
        "broadcast",
        "-a",
        down ? ACTION_DOWN : ACTION_UP,
        "--receiver-foreground",
        "-p",
        SYSTEMUI_PACKAGE,
        NULL,
    };

    pid_t child = fork();
    if (child < 0) {
        return false;
    }
    if (child == 0) {
        execve(arguments[0], arguments, environ);
        _exit(127);
    }

    int status;
    pid_t result;
    do {
        result = waitpid(child, &status, 0);
    } while (result < 0 && errno == EINTR);

    return result == child && WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static void fingerprint_down(int64_t *down_at_ms) {
    if (g_finger_down) {
        return;
    }
    if (!send_fingerprint_event(true)) {
        log_message("fingerprint down broadcast failed");
    }
    *down_at_ms = monotonic_ms();
    g_finger_down = true;
}

static void fingerprint_up(void) {
    if (!g_finger_down) {
        return;
    }
    if (!send_fingerprint_event(false)) {
        log_message("fingerprint up broadcast failed");
    }
    g_finger_down = false;
}

static bool install_signal_handlers(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;
    sigemptyset(&action.sa_mask);

    return sigaction(SIGTERM, &action, NULL) == 0 &&
           sigaction(SIGINT, &action, NULL) == 0 &&
           sigaction(SIGHUP, &action, NULL) == 0;
}

static int acquire_single_instance_lock(void) {
    int fd = open(LOCK_FILE, O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    if (fd < 0) {
        return -1;
    }
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--version") == 0) {
        puts("SysUIFPfix 1.2");
        return 0;
    }

    prctl(PR_SET_NAME, "SysUIFPfix", 0, 0, 0);

    int lock_fd = acquire_single_instance_lock();
    if (lock_fd < 0) {
        log_message("another instance is already running or lock creation failed");
        return 2;
    }
    if (!install_signal_handlers()) {
        log_message("failed to install signal handlers");
        close(lock_fd);
        return 3;
    }

    int kmsg_fd = open(KMSG_NODE, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (kmsg_fd < 0) {
        log_message("cannot open /dev/kmsg; root/SELinux access is required");
        close(lock_fd);
        return 4;
    }
    if (lseek(kmsg_fd, 0, SEEK_END) < 0) {
        log_message("cannot seek to the end of /dev/kmsg");
        close(kmsg_fd);
        close(lock_fd);
        return 5;
    }

    int epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (epoll_fd < 0) {
        log_message("epoll_create1 failed");
        close(kmsg_fd);
        close(lock_fd);
        return 6;
    }

    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    event.events = EPOLLIN;
    event.data.fd = kmsg_fd;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, kmsg_fd, &event) != 0) {
        log_message("epoll_ctl failed");
        close(epoll_fd);
        close(kmsg_fd);
        close(lock_fd);
        return 7;
    }

    log_message("using /dev/kmsg fingerprint-event fallback");
    int64_t down_at_ms = 0;
    char buffer[4096];

    while (!g_stop) {
        int timeout_ms = -1;
        if (g_finger_down) {
            int64_t remaining = down_at_ms + UP_TIMEOUT_MS - monotonic_ms();
            if (remaining <= 0) {
                fingerprint_up();
                continue;
            }
            timeout_ms = remaining > INT32_MAX ? INT32_MAX : (int)remaining;
        }

        int ready = epoll_wait(epoll_fd, &event, 1, timeout_ms);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            log_message("epoll_wait failed");
            struct timespec delay = {.tv_sec = 0, .tv_nsec = 100000000};
            nanosleep(&delay, NULL);
            continue;
        }
        if (ready == 0) {
            fingerprint_up();
            continue;
        }

        ssize_t length = read(kmsg_fd, buffer, sizeof(buffer) - 1);
        if (length < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            log_message("read from /dev/kmsg failed");
            continue;
        }
        if (length == 0) {
            continue;
        }

        buffer[length] = '\0';
        if (strstr(buffer, "fingerprint down") != NULL) {
            fingerprint_down(&down_at_ms);
        } else if (strstr(buffer, "fingerprint up") != NULL) {
            fingerprint_up();
        }
    }

    fingerprint_up();
    close(epoll_fd);
    close(kmsg_fd);
    close(lock_fd);
    log_message("stopped");
    return 0;
}
