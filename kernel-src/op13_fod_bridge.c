// SPDX-License-Identifier: GPL-2.0
/*
 * OnePlus 13 under-display fingerprint touch bridge.
 *
 * The Oplus touch driver already publishes FOD-only down/up transitions on a
 * blocking notifier chain.  Mirror those transitions to a dedicated input
 * device so Android can consume them without polling
 * /dev/kmsg or depending on a changing touchpanel event number.
 */

#include <linux/atomic.h>
#include <linux/err.h>
#include <linux/input.h>
#include <linux/kprobes.h>
#include <linux/module.h>
#include <linux/notifier.h>
#include <linux/stddef.h>

#define EVENT_ACTION_FOR_FINGERPRINT 0x01
#define OP13_FOD_KEY KEY_F5

struct touchpanel_event {
	int touchpanel_id;
	int x;
	int y;
	int fid;
	char type;
	int touch_state;
	int area_rate;
	int touch_early_down_flag;
	long is_touch_fp_area_cnt;
	ktime_t touch_fp_area_time;
	ktime_t fp_down_time;
	int tp_firmware_time;
};

typedef int (*touchpanel_notifier_fn_t)(struct notifier_block *nb);

static struct input_dev *fod_input;
static atomic_t last_touch_state = ATOMIC_INIT(0);
static touchpanel_notifier_fn_t register_touchpanel_notifier;
static touchpanel_notifier_fn_t unregister_touchpanel_notifier;
static bool notifier_registered;

static void *resolve_symbol(const char *name)
{
	struct kprobe probe = {
		.symbol_name = name,
	};
	void *address;
	int ret;

	ret = register_kprobe(&probe);
	if (ret)
		return ERR_PTR(ret);

	address = probe.addr;
	unregister_kprobe(&probe);
	return address ?: ERR_PTR(-ENOENT);
}

static int op13_fod_event(struct notifier_block *nb, unsigned long action,
			  void *data)
{
	const struct touchpanel_event *event = data;
	int state;

	(void)nb;
	if (action != EVENT_ACTION_FOR_FINGERPRINT || !event || !fod_input)
		return NOTIFY_DONE;

	state = READ_ONCE(event->touch_state) ? 1 : 0;
	if (atomic_xchg(&last_touch_state, state) == state)
		return NOTIFY_OK;

	input_report_key(fod_input, OP13_FOD_KEY, state);
	input_sync(fod_input);
	return NOTIFY_OK;
}

static struct notifier_block fod_notifier = {
	.notifier_call = op13_fod_event,
};

static int __init op13_fod_bridge_init(void)
{
	void *address;
	int ret;

	static_assert(offsetof(struct touchpanel_event, touch_state) == 20);

	address = resolve_symbol("touchpanel_event_register_notifier");
	if (IS_ERR(address))
		return PTR_ERR(address);
	register_touchpanel_notifier = (touchpanel_notifier_fn_t)address;

	address = resolve_symbol("touchpanel_event_unregister_notifier");
	if (IS_ERR(address))
		return PTR_ERR(address);
	unregister_touchpanel_notifier = (touchpanel_notifier_fn_t)address;

	fod_input = input_allocate_device();
	if (!fod_input)
		return -ENOMEM;

	fod_input->name = "op13_fod_bridge";
	fod_input->phys = "op13_fod/input0";
	fod_input->id.bustype = BUS_HOST;
	fod_input->id.vendor = 0x22d9;
	fod_input->id.product = 0x0013;
	fod_input->id.version = 0x0001;
	input_set_capability(fod_input, EV_KEY, OP13_FOD_KEY);

	ret = input_register_device(fod_input);
	if (ret) {
		input_free_device(fod_input);
		fod_input = NULL;
		return ret;
	}

	ret = register_touchpanel_notifier(&fod_notifier);
	if (ret) {
		input_unregister_device(fod_input);
		fod_input = NULL;
		return ret;
	}
	notifier_registered = true;

	pr_info("op13_fod_bridge: active\n");
	return 0;
}

static void __exit op13_fod_bridge_exit(void)
{
	if (notifier_registered && unregister_touchpanel_notifier) {
		unregister_touchpanel_notifier(&fod_notifier);
		notifier_registered = false;
	}
	if (fod_input) {
		if (atomic_xchg(&last_touch_state, 0)) {
			input_report_key(fod_input, OP13_FOD_KEY, 0);
			input_sync(fod_input);
		}
		input_unregister_device(fod_input);
		fod_input = NULL;
	}
	pr_info("op13_fod_bridge: stopped\n");
}

module_init(op13_fod_bridge_init);
module_exit(op13_fod_bridge_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("MIO");
MODULE_DESCRIPTION("Event-driven OnePlus 13 FOD touch bridge");
MODULE_VERSION("1.0.0");
MODULE_SOFTDEP("pre: oplus_bsp_tp_notify");
