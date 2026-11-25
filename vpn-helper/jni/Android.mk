# Builds librobomori-vpn.so: hev-socks5-tunnel, as fetch.sh downloads it, with the helper's JNI calls in place of its own.
HEV := $(call my-dir)/../build/hev-socks5-tunnel
include $(HEV)/third-part/yaml/Android.mk
include $(HEV)/third-part/lwip/Android.mk
include $(HEV)/third-part/hev-task-system/Android.mk

LOCAL_PATH := $(HEV)
SRCDIR := $(LOCAL_PATH)/src
REV_ID := 2.20.0
include $(LOCAL_PATH)/build.mk

include $(CLEAR_VARS)
LOCAL_MODULE := robomori-vpn
LOCAL_SRC_FILES := $(filter-out src/hev-jni.c,$(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))) ../../jni/tunnel.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/src $(LOCAL_PATH)/src/misc $(LOCAL_PATH)/src/core/include \
    $(LOCAL_PATH)/third-part/yaml/include $(LOCAL_PATH)/third-part/lwip/src/include \
    $(LOCAL_PATH)/third-part/lwip/src/ports/include $(LOCAL_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY -fno-stack-clash-protection $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--build-id=none
include $(BUILD_SHARED_LIBRARY)
