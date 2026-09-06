# Retention config adapter

Declare `retention-core` (or a selective retention module / umbrella) alongside `suite-firebase`, then install `RetentionRemoteConfig(FirebaseRetentionConfigSource())` as a core module or set the umbrella's `RetentionKitOptions.configSource`.

The default Remote Config parameter is `retention_config`. Its document is the core version-1 patch, for example:

```json
{"version":1,"overrides":{"notifications.enabled":true,"widgets.enabled":true,"review.max_attempts":3}}
```

Only console-supplied `VALUE_SOURCE_REMOTE` values count. Missing/deleted/default values and fetch failures preserve core's existing cached override snapshot. Explicit `removeKeys` restores module defaults. Parsing, complete-profile validation, persistence, stale generation/revision rejection and timeout are owned by `RetentionRemoteConfig` in core; this adapter does not maintain a second cache or create a second Firebase client.

Fetching uses the existing suite `RemoteConfigClient.fetchOnce`, shared with ad/paywall sources. Concurrent callers elect one leader atomically; success is memoized for the launch. Failure/cancellation releases followers and permits a later retry, and a short follower timeout does not cancel a longer leader. Historical OnboardKit/app-flag fetchers outside this client are unchanged.

`retention-core` is compileOnly for suite-firebase: analytics/paywall/ad-only consumers do not inherit Retention UI modules. Load `FirebaseRetentionConfigSource` only when core is declared. Cancellation closes only this source's coroutine scope. Functional flow does not depend on Firebase availability at Application startup.
