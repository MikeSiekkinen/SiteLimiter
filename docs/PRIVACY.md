# Privacy over all

Site Limiter processes browsing information only to enforce the user's local limits. Privacy takes precedence over additional diagnostics or convenience features.

- No network permission, telemetry, advertising, remote crash reports, accounts or data exports.
- Read the address bar only in user-selected browsers. Inspect other active windows only for their package identity, never their text. The Android accessibility permission itself remains broad.
- Keep full address-bar text transient. Persist only configured rules, browser selection, necessary budget state and active exceptions in private app storage. Prune old counters and expired exceptions; deleting a rule removes its stored usage.
- Exclude app data from cloud backup and device transfer using both Android backup formats. This complements `allowBackup=false`; it does not promise protection against root access or a compromised OS.
- Never log domains, URLs, app/package selections, usage durations, input, UI nodes, intent extras or serialized state. This applies to release and debug builds.
- Diagnostics may contain fixed event identifiers and stack-frame class/method/line locations. Never pass a caught Throwable directly to a logger: messages, causes and suppressed exceptions can embed user data.
- Use synthetic data in tests and reports. Do not ask users to upload browsing logs or accessibility tree dumps from real sessions.

Review new dependencies and every path that can disclose data against these rules. Changes to permissions, persistence or logging require an explicit privacy review. The Android OS and third-party libraries can produce their own logs; this policy governs the app's code and requires dependency review, not a claim that the OS emits no diagnostics.
