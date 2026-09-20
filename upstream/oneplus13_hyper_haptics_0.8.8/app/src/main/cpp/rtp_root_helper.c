// SPDX-License-Identifier: GPL-2.0-only
/*
 * OnePlus 13 RichTap RTP player.
 *
 * This process is started through su only while the HyperOS Settings haptic
 * video is playing.  It talks to the stock qcom-hv-haptics RichTap misc
 * device; it does not replace the vibrator HAL and never runs as a daemon.
 */

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define RICHTAP_DEVICE "/dev/awinic_haptic"
#define RICHTAP_SETTING_GAIN 0x5205
#define RICHTAP_RTP_MODE 0x5209
#define RICHTAP_STREAM_MODE 0x520a
#define RICHTAP_STOP_MODE 0x5212
#define RICHTAP_DEFAULT_GAIN 128u

#define MMAP_BYTES (4096u << 2)
#define SLOT_COUNT 4u
#define SLOT_DATA_BYTES 1000u
#define DIRECT_DATA_BYTES 3996u

#define SLOT_VALID 0x55u
#define SLOT_FINISHED 0xaau
#define SLOT_INVALID 0xffu

#pragma pack(push, 4)
struct rtp_slot {
    volatile uint8_t status;
    uint8_t bit;
    int16_t length;
    uint32_t reserved;
    struct rtp_slot *kernel_next;
    struct rtp_slot *user_next;
    uint8_t data[SLOT_DATA_BYTES];
};
#pragma pack(pop)

_Static_assert(sizeof(struct rtp_slot) == 1024, "RichTap slot ABI mismatch");

static volatile sig_atomic_t interrupted;
static int device_fd = -1;

static void handle_signal(int signal_number) {
    (void) signal_number;
    interrupted = 1;
}

static int64_t monotonic_millis(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return (int64_t) value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

static void sleep_millis(unsigned milliseconds) {
    struct timespec delay = {
            .tv_sec = milliseconds / 1000,
            .tv_nsec = (long) (milliseconds % 1000) * 1000000L,
    };
    while (!interrupted && nanosleep(&delay, &delay) != 0 && errno == EINTR) {
    }
}

static int read_all(int fd, uint8_t *destination, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        ssize_t count = read(fd, destination + offset, size - offset);
        if (count == 0) return -1;
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        offset += (size_t) count;
    }
    return 0;
}

static int wait_for_status(volatile struct rtp_slot *slot, uint8_t status,
                           unsigned timeout_ms) {
    int64_t deadline = monotonic_millis() + timeout_ms;
    while (!interrupted) {
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (slot->status == status) return 0;
        if (monotonic_millis() >= deadline) {
            errno = ETIMEDOUT;
            return -1;
        }
        sleep_millis(1);
    }
    errno = EINTR;
    return -1;
}

static int play_direct(int input_fd, size_t file_size) {
    uint8_t packet[4000] = {0};
    uint32_t length = (uint32_t) file_size;
    memcpy(packet, &length, sizeof(length));
    if (read_all(input_fd, packet + sizeof(length), file_size) != 0) {
        perror("read RTP");
        return 4;
    }
    if (ioctl(device_fd, RICHTAP_RTP_MODE, packet) != 0) {
        perror("RICHTAP_RTP_MODE");
        return 5;
    }

    unsigned duration_ms = (unsigned) ((file_size + 23u) / 24u);
    sleep_millis(duration_ms + 150u);
    return interrupted ? 130 : 0;
}

static int finish_stream(volatile struct rtp_slot *slot) {
    if (wait_for_status(slot, SLOT_INVALID, 3000) != 0) {
        perror("wait for finish slot");
        return -1;
    }
    slot->length = 0;
    __atomic_thread_fence(__ATOMIC_RELEASE);
    slot->status = SLOT_FINISHED;
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    return 0;
}

static int play_stream(int input_fd, size_t file_size) {
    void *mapping = mmap(NULL, MMAP_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED,
                         device_fd, 0);
    if (mapping == MAP_FAILED) {
        perror("mmap RichTap ring");
        return 6;
    }

    volatile struct rtp_slot *slots = (volatile struct rtp_slot *) mapping;
    if (ioctl(device_fd, RICHTAP_STREAM_MODE, 0) != 0) {
        perror("RICHTAP_STREAM_MODE");
        munmap(mapping, MMAP_BYTES);
        return 7;
    }

    size_t remaining = file_size;
    unsigned index = 0;
    int result = 0;
    while (remaining > 0 && !interrupted) {
        volatile struct rtp_slot *slot = &slots[index];
        if (wait_for_status(slot, SLOT_INVALID, 3000) != 0) {
            perror("wait for RichTap slot");
            result = 8;
            break;
        }

        size_t count = remaining < SLOT_DATA_BYTES ? remaining : SLOT_DATA_BYTES;
        if (read_all(input_fd, (uint8_t *) slot->data, count) != 0) {
            perror("read RTP stream");
            result = 9;
            break;
        }
        /* The PMIC FIFO is programmed in four-byte bursts.  Zero padding is
         * inaudible and prevents the driver dropping a 1-3 byte tail. */
        size_t padded = (count + 3u) & ~3u;
        if (padded > count) {
            memset((uint8_t *) slot->data + count, 0, padded - count);
        }
        slot->length = (int16_t) padded;
        __atomic_thread_fence(__ATOMIC_RELEASE);
        slot->status = SLOT_VALID;
        __atomic_thread_fence(__ATOMIC_SEQ_CST);

        remaining -= count;
        index = (index + 1u) % SLOT_COUNT;
    }

    if (result == 0 && !interrupted) {
        if (finish_stream(&slots[index]) != 0) result = 10;
        /* The kernel waits for its hardware FIFO to drain and then marks all
         * four slots finished.  Keep the mapping alive until that happens. */
        if (result == 0) {
            int64_t deadline = monotonic_millis() + 3000;
            while (!interrupted && monotonic_millis() < deadline) {
                __atomic_thread_fence(__ATOMIC_ACQUIRE);
                bool finished = true;
                for (unsigned i = 0; i < SLOT_COUNT; ++i) {
                    if (slots[i].status != SLOT_FINISHED) {
                        finished = false;
                        break;
                    }
                }
                if (finished) break;
                sleep_millis(2);
            }
        }
    }

    if (interrupted || result != 0) {
        (void) ioctl(device_fd, RICHTAP_STOP_MODE, 0);
    }
    munmap(mapping, MMAP_BYTES);
    return interrupted ? 130 : result;
}

static int stop_playback(void) {
    device_fd = open(RICHTAP_DEVICE, O_RDWR | O_CLOEXEC);
    if (device_fd < 0) {
        perror("open " RICHTAP_DEVICE);
        return 2;
    }
    int result = ioctl(device_fd, RICHTAP_STOP_MODE, 0);
    if (result != 0) perror("RICHTAP_STOP_MODE");
    close(device_fd);
    device_fd = -1;
    return result == 0 ? 0 : 3;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--stop") == 0) return stop_playback();
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: %s RTP_FILE [GAIN_1_TO_128]\n", argv[0]);
        fprintf(stderr, "       %s --stop\n", argv[0]);
        return 1;
    }

    char *end = NULL;
    long gain = argc == 3 ? strtol(argv[2], &end, 10) : 128;
    if (gain < 1 || gain > 128 || (argc == 3 && (!end || *end != '\0'))) {
        fprintf(stderr, "invalid gain: %s\n", argc == 3 ? argv[2] : "");
        return 1;
    }

    int input_fd = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (input_fd < 0) {
        perror("open RTP file");
        return 2;
    }
    struct stat info;
    if (fstat(input_fd, &info) != 0 || info.st_size <= 0) {
        perror("stat RTP file");
        close(input_fd);
        return 2;
    }

    device_fd = open(RICHTAP_DEVICE, O_RDWR | O_CLOEXEC);
    if (device_fd < 0) {
        perror("open " RICHTAP_DEVICE);
        close(input_fd);
        return 2;
    }

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);
    signal(SIGHUP, handle_signal);

    if (ioctl(device_fd, RICHTAP_SETTING_GAIN, (unsigned long) gain) != 0) {
        perror("RICHTAP_SETTING_GAIN");
        close(device_fd);
        close(input_fd);
        return 3;
    }

    size_t file_size = (size_t) info.st_size;
    int result = file_size <= DIRECT_DATA_BYTES
            ? play_direct(input_fd, file_size)
            : play_stream(input_fd, file_size);

    if (interrupted) (void) ioctl(device_fd, RICHTAP_STOP_MODE, 0);
    /* RICHTAP_SETTING_GAIN is global driver state, not scoped to this file
     * descriptor.  Leaving a video/test gain behind makes unrelated framework
     * haptics quiet until another client happens to reprogram it. */
    if (ioctl(device_fd, RICHTAP_SETTING_GAIN, RICHTAP_DEFAULT_GAIN) != 0) {
        perror("restore RICHTAP_SETTING_GAIN");
        if (result == 0) result = 11;
    }
    close(device_fd);
    close(input_fd);
    device_fd = -1;
    return result;
}
