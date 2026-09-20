#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static volatile sig_atomic_t running = 1;

static void handle_stop(int signal_number) {
    (void)signal_number;
    running = 0;
}

static void run_setup(void) {
    printf("Initializing driver and settings...\n");

    (void)system("settings put global gesture_newdoubletap_support 1");
    (void)system("settings put secure oplus_customize_gesture_double_touch_on 1");
    (void)system("settings put secure oplus_customize_gesture_double_touch_off 1");
    (void)system("settings put secure oplus_customize_gesture_open_type 1");
    (void)system("settings put secure oplus_customize_gesture_double_touch 1");
    (void)system("settings put secure double_tap_to_wake 1");

    (void)system("/odm/bin/touchHidlTest -c ao 0 1");
    (void)system("/odm/bin/touchHidlTest -c ro 0 1");
    (void)system("/odm/bin/touchHidlTest -c wo 0 1 1");
    (void)system("/odm/bin/touchHidlTest -c ro 0 21");
    (void)system("/odm/bin/touchHidlTest -c ao 0 39");
    (void)system("/odm/bin/touchHidlTest -c wo 0 21 2");

    printf("Setup complete; entering idle sleep.\n");
    fflush(stdout);
}

int main(void) {
    signal(SIGTERM, handle_stop);
    signal(SIGINT, handle_stop);

    run_setup();

    while (running) {
        pause();
    }

    // Both SystemUI gesture paths use this as their shared enable switch.
    (void)system("settings put secure double_tap_to_wake 0");
    return 0;
}
