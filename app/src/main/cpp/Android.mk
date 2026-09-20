LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := op13_lhdc_patch
LOCAL_SRC_FILES := lhdc_patch.cpp
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden -fno-exceptions -fno-rtti -mbranch-protection=standard -Wall -Wextra -Werror
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
