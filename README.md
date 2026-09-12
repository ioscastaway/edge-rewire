# edge-rewire

An accessibility service that gives Samsung Internet the edge gestures of Safari on iOS: left edge
goes back, right edge goes forward, and the root of a tab is a wall, not an exit.

> I switched phones and my thumb did not get the memo. Every right-edge swipe was a back gesture,
> and every back gesture at the first page threw me out of the browser. So naturally, I
> rationalized the annoyance through engineering.

## Why I built this

Three habits from iOS Safari that gesture navigation on Android does not have:

| Gesture | iOS Safari | Android (gesture navigation) |
|---|---|---|
| Swipe in from the left edge | Back one page | Back |
| Swipe in from the right edge | Forward one page | Back |
| Back at the first page of a tab | Nothing happens | Leaves the browser |

Samsung Internet has no swipe-to-navigate setting and One UI has no "back on one side only"
option, and the Good Lock route (One Hand Operation+) felt worse than the problem. This
repository asks whether a third-party app, with no root and no special OEM privileges, can change
the rules for one specific app and leave the rest of the phone alone.

## The iOS brain

On iOS this is not something an app gets to touch. The interactive back gesture belongs to
`UINavigationController`, edge gestures belong to the app that owns the screen, and no app can put
an invisible view over Safari, read Safari's toolbar, or press Safari's buttons. The system
enforces this by architecture, not by policy: there is no public API for cross-app overlays or
cross-app UI trees at all.

## What Android exposes

Everything here is a public API. The interesting part is how they compose.

- **`TYPE_ACCESSIBILITY_OVERLAY` windows.** An enabled accessibility service may add windows above
  other apps without the "draw over other apps" permission. They receive touches.
- **`View.setSystemGestureExclusionRects`.** A window can ask the system not to treat part of its
  area as the back-gesture zone. The window manager caps this at 200dp per edge for normal
  windows, which would leave most of the edge to the system.
- **The cap is lifted** for windows that request hidden navigation bars with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`. This is the path fullscreen games use. The check is on
  the window's *requested* state, and our strip is not focusable, so it never becomes the insets
  control target and the real navigation bar is unaffected. Verified on both test devices: the
  display's exclusion region became the full height of both strips.
- **Cross-app accessibility tree.** `findAccessibilityNodeInfosByViewId` finds Samsung Internet's
  own toolbar buttons, `isEnabled` says whether there is history, and `ACTION_CLICK` presses them.
- **`dispatchGesture`** for the cases where the toolbar is on screen but not in the tree: a small
  nudge so a hidden toolbar comes back, then a tap where the button was last seen.

## Experiment

While Samsung Internet is the focused application window, two full-height strips sit on the left
and right screen edges, each as wide as the system's own back-gesture zone plus 2dp (78px + 5px on
the Fold 8 at default sensitivity; read from the window's system-gesture insets, not guessed). Everything else on the phone keeps the stock gestures, because
the strips are removed the moment another app comes to the front.

| Swipe | Toolbar in the accessibility tree | Toolbar not in the tree (page has been scrolled) |
|---|---|---|
| From the left edge | Click the Back button if enabled, otherwise do nothing | Nudge the page 64dp so a hidden toolbar comes back, then tap where Back was last seen |
| From the right edge | Click the Forward button if enabled, otherwise do nothing | Same nudge, then tap where Forward was last seen |

In both columns a disabled button means nothing happens. In the first column that is our decision;
in the second it is the browser's, because a tap on a disabled button is swallowed. Either way the
system back action is never sent, so the root of a tab can never turn into an app exit.

## Architecture

```
Settings > Accessibility ──binds──▶ EdgeRewireService
                                       │
        TYPE_WINDOW_STATE_CHANGED /    │ which application window is focused, on which display
        TYPE_WINDOWS_CHANGED ──────────┤
                                       ├─ target in front ──▶ EdgeOverlay(LEFT) + EdgeOverlay(RIGHT)
                                       │                       full-height strips, gesture-excluded
                                       │                       SwipeDetector (pure Kotlin) classifies
                                       │                                    │ inward swipe
                                       │                                    ▼
                                       │                       BrowserNavigator
                                       │                         action_backward / action_forward
                                       │                         enabled? click : do nothing
                                       │                         absent?  nudge, then tap last known bounds
                                       └─ target gone ────────▶ strips removed, system gestures back
```

- `gesture/` is pure Kotlin and unit-tested (`SwipeDetectorTest`).
- `platform/` touches Android: the service, the overlay windows, the toolbar lookup.
- `ui/` is a debug console: service state, knobs (edge width, commit distance, exclusion mode,
  strip tint, target packages), and the service log.

## What I learned

1. **The 200dp exclusion cap is a request-state check, not a focus check.** A non-focusable
   accessibility overlay that asks for hidden navigation bars gets unrestricted exclusion on stock
   Android 16 and on One UI 9 alike. `dumpsys window displays` shows it directly:
   `mSystemGestureExclusion=SkRegion((0,0,52,1972)(1196,0,1248,1972))`.
2. **While your finger is on an accessibility overlay, the overlay is the "active window".**
   `rootInActiveWindow` at `ACTION_UP` returned our own empty strip, not the browser. Look the
   target up through the focused application window in `getWindows()` instead.
3. **Samsung Internet's toolbar leaves the accessibility tree when it auto-hides on scroll, and
   does not come back when the toolbar does.** After the first hide, the toolbar you see on a
   scrolled page is drawn by the browser itself (Chromium's compositor-drawn browser controls);
   the Android views stay hidden until the page is scrolled back to the top or a navigation
   happens. `clearCache()` and `setCacheEnabled(false)` change nothing, because it is the app's own
   view state, not a stale cache. Root dumps went from three children to one and stayed there.
4. **A cached "history exists" is dangerous.** The first workaround remembered the last toolbar
   state and sent the system back action on a scrolled page. It went stale after one navigation
   and exited the browser at the root. That version was deleted the same evening.
5. **Tapping the drawn toolbar works, and a disabled button is a safe target.** `dispatchGesture`
   at the button's last known screen bounds presses the compositor-drawn button, navigation
   happens, and the views come back into the tree. At the root the Back button is disabled and
   the tap is swallowed. That is the same guarantee as the accessibility click, obtained from a
   different layer.
6. **The toolbar ids are `action_backward` and `action_forward`** with Korean descriptions on this
   device. Discovered from the service log, then hardcoded with a description-based fallback.
7. **Injected swipes and real fingers are not the same test.** Every `adb shell input swipe`
   started at x=5 and reached a 20dp strip. The first real thumb landed about 25dp from the edge:
   outside the strip, inside the system's 30dp back-gesture zone, and the browser exited at the
   root. The strip now sizes itself from the system-gesture insets it receives, which on this
   device converge to the real zone width in a few relayouts. Swipes injected at 65px from the
   edge are now caught.
8. **Enable the service while the browser is already open and nothing happens** until the next
   window event, because a fresh service has no idea what is in front. The service now evaluates
   the foreground once, right after it connects.

## iOS comparison

- iOS has no public API for an app to overlay another app, read its view hierarchy, or press its
  controls. The closest surfaces are app extensions and Shortcuts, neither of which can change how
  Safari responds to a gesture.
- Android exposes all of it, gated behind one user-granted accessibility toggle plus, on API 33+,
  the restricted-settings gate for sideloaded apps.
- The difference is architectural (iOS does not model cross-app UI access) and policy-level
  (Android allows it but keeps it behind an explicit, revocable grant).
- To be precise about the original complaint: iOS Safari's forward swipe is an app-level feature,
  not a system gesture. Samsung Internet could ship the same thing tomorrow without any of this.

## Limitations

- **Samsung Internet only**, by design; the target list is editable in the debug screen and the
  same build was run against Chrome on the emulator, where no toolbar buttons exist and the
  strips correctly do nothing.
- **Taps on the outer ~32dp of the screen do not reach the browser** while the strips are up.
  That is the same area the system already reserves for its back gesture, so nothing new is lost,
  but a page's edge-hugging controls were never reachable by tap in gesture navigation anyway.
- **One UI's Edge panel handle** shares the right edge. Inside the browser our strip is on top.
- **The scrolled-page path nudges the page by 64dp** before tapping. It is visible, and if the
  toolbar somehow does not come back the tap lands on page content near the bottom edge.
- **The tap path needs the button bounds**, learned the first time the toolbar is in the tree
  after the strips attach. Until then a swipe on a scrolled page does nothing and says so in the
  log. Bounds are forgotten when the strips detach, so a display change relearns them.
- **Verified on one OEM build**: Galaxy Z Fold 8, One UI 9.0, Android 17, Samsung Internet
  30.0.2.61, gesture navigation. The exclusion trick was also verified on a stock Android 16
  emulator. Toolbar ids will change with browser updates; the log shows what it found.
- **Fold/unfold** moves the focused window to another display. The service re-attaches on the
  display of the focused window on the next window event, but this was not exercised by hand.
- Injected input was used for most runs; see "What I learned" 7.
- A debug broadcast (`adb shell am broadcast -a com.ioscastaway.edgerewire.DUMP`) logs the
  target's tree. It exists to answer questions like item 3 above and does nothing else.

## Security and privacy

- Permissions: the accessibility service only. No `SYSTEM_ALERT_WINDOW`, no internet permission.
- What it reads: which application window is focused, and the enabled state of two toolbar
  buttons in the target app, at the moment of a swipe. It does not read page content, URLs, or
  text, and subscribes to window events only, not content-change events.
- What it does: clicks those two buttons; on a scrolled page, dispatches one short scroll gesture
  and one tap at the button's last known position. It never sends the system back action. The
  "fallback to system back" switch in the debug screen is wired but off, and the current code
  path does not reach it.
- Everything stays on the device. Nothing is stored beyond the settings knobs.
- `isAccessibilityTool` is not set: this is not an assistive technology and should not receive
  views marked `accessibilityDataSensitive`.

## Setup

```bash
./scripts/bootstrap.sh
./gradlew assembleDebug
adb install -i com.android.vending -r app/build/outputs/apk/debug/app-debug.apk
```

Then Settings > Accessibility > Installed apps > Edge Rewire > On (on a sideloaded build, allow
restricted settings from the app info page first). Open Samsung Internet and swipe from an edge.
The debug screen shows the service log; `adb logcat -s EdgeRewire` shows the same.

## Verdict

Genuinely useful, with one visible seam. On the Fold 8 the three complaints in the first table
are gone: left edge goes back, right edge goes forward, and hammering back at the first page
leaves you exactly where you were. Outside Samsung Internet nothing changed. The seam is the
64dp nudge on a scrolled page, which is the price of Samsung Internet drawing its toolbar
somewhere accessibility cannot see.

The interesting part is not the gestures. It is that a third-party app, with one user-granted
toggle, can carve a per-app exception out of system navigation and put it back when the app
leaves. iOS does not have a layer where that sentence could be written.

Verified on-device sequence (injected input, One UI 9.0, Samsung Internet 30.0.2.61): back and
forward from the toolbar; back and forward on a scrolled page via nudge and tap; six consecutive
back swipes on a scrolled page ending at the root with the browser still in front; strips absent
in every other app.

---

**Reason #NN I don't regret switching to Android:**
The system's own gestures are negotiable, one app at a time.

*(Number to be assigned in the profile README index.)*
