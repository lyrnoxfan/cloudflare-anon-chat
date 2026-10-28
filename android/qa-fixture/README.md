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
