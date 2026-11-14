# Bounded Compose frame-waiter diagnostic (test branch only)

This is a source-attribution probe, **not a CPU fix**. It preserves the drawer metadata isolation.

## Trigger / stop

The existing functional diagnostics switch defaults off. On a false-to-true transition (or a cold
process start with that preference already on), a single attempt is armed. The **first chat page
RESUMED** claims it, waits 3 seconds, then captures for 10 seconds. Settings pages cannot consume it.
Leaving/resuspending that chat or switching diagnostics off stops it immediately; the attempt is
consumed even if cancelled during the delay. Resuming or a new generation does not restart it.
Turn functional diagnostics off then on to explicitly arm another attempt. No new UI or ticker.

## What is observed

AGP's public ASM instrumentation API (ALL dependencies, clma only) inserts a no-throw static call at
the **actual Compose 1.12 BroadcastFrameClock.withFrameNanos entry**, before original code. This
counts waiting-registration attempts, not completed frames, draws, UI commits or CPU samples.
Original operands, callbacks, continuations, exceptions and cancellation code are not wrapped.

Selected app UI, animation-core and Haze source methods additionally tag the original callback by
identity immediately before a real frame-wait invocation. The literal pre-R8 class/method/line and
category survive obfuscation. An identity tag is consumed only when the same callback reaches the
broadcast hook. `site_calls` and `registrations` are therefore deliberately separate: e.g. a Haze
node can use a different clock and only appear as a tagged request site. These categories are
immediate registration sites, **not necessarily the originating Composable**. `animation_frame`
does not assert a finite/infinite duration; `unattributed` means no matched tag, not no requester.

`FrameWaiterDiag` emits armed/started and a single final batch. Source lines contain stable sites,
categories, registration/site counts. Up to eight first-seen broadcast sources retain one stack
each (16 class/method/line entries, no values, source file paths, callback.toString or chat content).
There are at most 32 source keys plus an overflow bucket, 64 identity tags, saturating counters.
Closed/default-off hook returns before timestamps, class lookup, allocations or stack collection.
An exceptional hook failure disables recording and reports failed=1; it never replaces application
exceptions. Deadline checks reject events >=10 seconds even if main-thread congestion delays output.

## Coverage / build proof

This does **not** cover all View.invalidate, Haze effect invalidations, delayed snapshot writes,
RenderThread, image drawable animations or GPU work. Site tags cover selected Haze frame-wait calls,
not all Haze preDraw/invalidation paths. A zero waiter count is not a clean-bill-of-health result.
No sampling/permission bypass is involved. clma overlay explicitly permits shell profiling without
debuggable, version or UI changes (the previous e22 sidebar package did not include that overlay).

CI tests the ASM visitor and recorder; the final optimized ARM64 APK is parsed to require the real
entry hook plus InfiniteTransition, SuspendAnimation and HazeSourceNode site tags. ABI mismatch or
missing hook fails CI, rather than shipping a misleading empty probe. Matching R8 mapping and
verification JSON are retained with that exact run/head. Source snapshots: official Google Maven
runtime/animation 1.12.0 and Maven Central Haze 2.0.1, not replacement libraries.
