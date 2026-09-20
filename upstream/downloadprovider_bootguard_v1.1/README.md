# DownloadProvider Boot Guard

An LSPosed/Vector module for Xiaomi HyperOS 4 DownloadProvider running in
`android.process.media`.

It does not replace or modify `DownloadProvider.apk` and has no background
service, polling loop, permissions, or user interface.

Hooks:

- `CloudConfigPreference.getBootKillTimeout()` returns `-1`.
- `CloudConfigPreference.setBootKillTimeout(long)` stores `-1` instead of the
  cloud-provided boot-kill timeout. The rest of the cloud configuration is not
  intercepted.
- A framework `SharedPreferences` fallback matches only the
  `boot_kill_timeout` key, so the guard survives Xiaomi class-name changes
  without suppressing any other cloud-control field.
- `BootHelper.inKillPeriod()` returns `false`.
- `BootHelper.checkProcessKill()` is suppressed as a narrow fallback.
- `XCrashlytics.killSelf()` is blocked only when its call stack or thread proves
  that it came from `BootHelper`; genuine crash-handler exits pass through.
- As an update-resistant final guard, self-directed `Process.killProcess()` /
  signal calls and `System`/`Runtime` exits are also blocked only while running
  on the `DMS_BootEvent` thread or a verified `BootHelper` call path.

The low-level guards are process-local and context-gated. They do not globally
disable process termination, so unrelated and genuine crashes retain Android's
original behavior.

All methods are checked for their exact signature. If a future ROM removes or
changes these classes, the module skips incompatible hooks and logs the result
instead of forcing a potentially unsafe hook.
