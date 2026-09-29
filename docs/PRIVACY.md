# Privacy over all

Site Limiter processes browsing information only to enforce the user's local limits. Privacy takes precedence over additional diagnostics or convenience features.

- No network permission, telemetry, advertising, remote crash reports, accounts or data exports.
- Read the address bar only in user-selected browsers. When leaving a blocked tab, also inspect native browser tab controls, menus and address-entry controls to close that tab or navigate it to a blank page. This bounded operation excludes web-content subtrees. Inspect other active windows only for their package identity, never their text. The Android accessibility permission itself remains broad.
- Keep full address-bar text transient. Persist only configured rules, browser selection, necessary budget state and active exceptions in private app storage. Prune old counters and expired exceptions; deleting a rule removes its stored usage.
- Exclude app data from cloud backup and device transfer using both Android backup formats. This complements `allowBackup=false`; it does not promise protection against root access or a compromised OS.
- Never log domains, URLs, app/package selections, usage durations, input, UI nodes, intent extras or serialized state. This applies to release and debug builds.
- Diagnostics may contain fixed event identifiers and stack-frame class/method/line locations. Never pass a caught Throwable directly to a logger: messages, causes and suppressed exceptions can embed user data.
- Tab cleanup retains its browser, host, address and window identity in memory only, until the block target is replaced, the block screen is dismissed, cleanup completes or the service disconnects. Its activity receives an opaque request token; no full URL is added to intents or storage. Native UI snapshots are discarded after each sample. Browser string resources are read locally for localized menu labels. No new permissions, dependencies or network access are required.
- Use synthetic data in tests and reports. Do not ask users to upload browsing logs or accessibility tree dumps from real sessions.

Review new dependencies and every path that can disclose data against these rules. Changes to permissions, persistence or logging require an explicit privacy review. The Android OS and third-party libraries can produce their own logs; this policy governs the app's code and requires dependency review, not a claim that the OS emits no diagnostics.
