# Android isolated QA fixture

This in-memory HTTP/WebSocket fixture serves only the `.qa` app's loopback base URL. It is not
part of the production service and does not proxy requests or use production credentials/data.

On the isolated Android emulator only:

1. Start the fixture from the repository root: `node android/qa-fixture/mock-server.mjs`.
2. Forward the emulator's local port: `adb reverse tcp:8000 tcp:8000`.
3. Install and launch the separately signed `.qa` package; its API base is `http://127.0.0.1:8000`.

The fixture holds sessions and sample lobby messages in memory and resets when it exits. It
supports the nickname gate, room state, date/grouped sample history, live socket
readiness/presence/ping, text sending, own-message deletion, and audio/image/video/file download
samples. It intentionally has no upload endpoint; uploaded content is not needed for these local
rendering and message-interaction checks. It binds to `127.0.0.1` only.
Do not point the production/debug app at this fixture or use it to test production traffic.


## Consolidated CI acceptance (0.2.5)

The manually dispatched `android.yml` now builds Debug + QA + AndroidTest from one frozen
commit, then uses a new ephemeral KVM Android 9 emulator. `ui-smoke.py` opens the actual
Activity, enters a nickname and text, checks reply/delete, drawer and emoji controls,
and captures light/dark screenshots and UIAutomator trees. This is a real Android UI
exercise against the fixture, not a JNI-only test and not a pixel-parity assertion.

`ci-acceptance.sh` then stops the fixture and starts the actual repository Worker with
isolated local D1/R2/DO storage using `wrangler.local.json`. The QA instrumentation's
`network=true` option is now guarded to this loopback build: it checks two native clients,
private-room permission, WebSocket message/delete events, idempotence and authorized image
upload/download. There are no Cloudflare credentials or remote operations in this workflow.
The Debug installable package still targets the configured live site; CI never launches it.

The CI artifacts include the exact APKs, hashes, manifest/signature tool output, emulator
logs, JNI/service output, UI results and screenshots. A failed smoke remains failed, even
if building/lint passed. No physical-device microphone/camera, remote SFU or dual-device
call success is implied by this workflow. Existing local emulators/apps are not cleared.

## Explicit remote native SFU acceptance

`android-sfu.yml` is manually dispatched only for the authorized `blue-room-test`
service at `https://chat.lyrnox.com`. It does not deploy or change cloud resources.
`-PqaSfu` changes only the isolated `.qa` build's endpoint and instrumentation
runner; normal debug/release and the default loopback QA builds are unchanged.
Two ephemeral Android 10 emulators run the shipped `NativeCallSession` and JNI
core independently. A unique private test room is used; no existing messages,
rooms, installs or data are cleared. The ephemeral room remains after testing.
The test requires both sides to decode at least ten video frames, receive audio
RTP packets and video bytes, exercise mute/camera enable state and leave the
server call. Emulated Camera2/microphone sources do not establish physical-device
capture, subjective audio quality or real-network phone behavior. No session
cookies, provider secrets, SDP, ICE credentials or raw WebRTC logcat are included
in the public artifacts. Run results, not the presence of this workflow, determine
whether acceptance passed.
