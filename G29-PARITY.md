
## Follow-up 2026-10-30: interaction/status defects
- f00c74d outage run 40993926782 passed all six packet-interruption checks (API29/local Worker; recovery 2.12s).
- Same product code visual diagnostic 40993931486 passed all three layouts; no menu-retry.json was generated, so no retry was needed. This does not establish the root cause or erase the earlier dark long-press failure.
- Delivery progress is now confined to the pending-message card; it no longer overwrites the connection indicator or leaves “正在发送…” after success.
- Deletion explicitly dismisses composer IME/focus and prevents a non-editable confirmation dialog from reopening it.
- Presence-only snapshots no longer invalidate unchanged message rows. This removes an identified held-touch cancellation risk; it is not proof of the historical failure's cause.
- Visual acceptance now asserts retained online state/no stale send banner and IME hidden during/after deletion; no reply/delete assertions were removed.
