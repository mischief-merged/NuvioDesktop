# NuvioRoid: what is different in this fork

NuvioRoid is a fork of [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop). It adds offline-viewing features to the desktop app and makes the existing download and playback flows easier to follow. Everything here is offered back to the upstream project; nothing has been submitted yet, and each change is kept on its own branch so it can be proposed and reviewed separately.

- **Based on:** upstream `Dev` at `c8ee7621` (14 November 2026).
- **Everything together:** the `downloads-suite` branch.
- **Scope:** desktop. Built and tested on macOS (Apple silicon). Shared code compiles for desktop only in this fork's test runs; the Android and iOS builds have not been run, and the Linux and Windows player bridges are unchanged.

## Downloads

| Change | What it does | Branch |
|---|---|---|
| Season download | One action on a series page downloads every released episode of a season, two at a time, with Stop and progress on the button. Sources are ranked per episode and the next one is tried when a file turns out to be bad. | `pr/c-season-download` |
| Saved preferences | Quality, maximum bitrate and audio language are remembered per profile and reused. A "Just this time" option keeps a one-off download from changing them. | `pr/c2-download-defaults` |
| Movies and movie sets | The same download dialog for a single movie, and for a collection with a checklist of which titles to fetch. | `pr/c3-movie-download`, `pr/c4-movie-set-download` |
| Auto-download | Per show: keep the next N unwatched episodes downloaded, within a storage limit that applies to automatic downloads only. | `pr/f-auto-download`, `pr/f2-auto-download-storage-limit` |
| Delete after watching | Optional per show. A watched episode is moved to the Trash after a delay, with Keep, a countdown on the row and a "Recently deleted" list. | `pr/g-delete-after-watching` |
| Speed limit | A global cap on download speed. | `pr/d-download-speed-limit` |
| File checks | A finished file is checked before it counts as downloaded: size, container type, running time, the hash or digest the source or server provides, audio languages, and a look at frames near the start and end to catch truncated, blank or single-image files. On macOS the frame look uses the player library the app already ships. | `pr/h-download-frame-check` |
| Safer state | Download state updates are serialised across threads; the stored list is written atomically with a fallback copy and decoded tolerantly; interrupted downloads resume on launch and retry when the connection returns. | `pr/b-downloads-concurrency` and later branches |
| Downloads screen | Rows say what is happening (waiting for the internet, checking the file, paused and why) and how a finished file was verified. Failures give a plain reason and a next step, including "Choose another source". Delete moves files to the Trash and names the item. An unreadable list is reported instead of shown as empty. Controls are named for assistive technology. | `fix/downloads-screen-states` |
| Clearer auto-download | Deleting an auto-downloaded episode does not fetch it again; rule rows open their settings; results are reported only after they are saved; closing the window while downloads are running asks first. | `fix/auto-download-clarity` |

## Playback

| Change | What it does | Branch |
|---|---|---|
| Play by preferences | A new auto-play mode, "Best match for my preferences", picks the source using the same saved quality, bitrate and language as downloads. If nothing fits, the source list opens and says so; when a source is picked, the loading screen names it. Manual stays the default. | `pr/i-auto-play-preferred` |
| Preferences in Settings | Settings, Playback, "Quality and language preferences" edits the saved values and the "When I press Play" choice. A one-time prompt offers the same choices to a new profile. | `pr/j-preferences-settings` |
| Source failover | When a source fails to start or dies during playback, the player moves to the next source in the same ranked order and resumes where it was. A short placeholder clip played in place of the real file counts as a failure. Bounded, never retries a failed source, waits when offline, and can be turned off. | `pr/l-playback-failover` |

## Navigation

| Change | What it does | Branch |
|---|---|---|
| Episode page and trail | Each episode has its own page: still, title, air date, runtime, rating, summary, Play or Resume, mark watched, previous and next episode, and the rest of the season. A `Show > Season > Episode` trail leads back. On desktop, clicking an episode opens its page and a Play button on the thumbnail plays it; the show page can open on a given season and episode. Episode downloads, Continue Watching entries and the player title link to the episode, season and show. | `pr/k-episode-navigation` |

## First run

| Change | What it does | Branch |
|---|---|---|
| Profile picker without an account | With no profiles, the picker shows the "Add Profile" tile and a line of guidance instead of an empty screen. Its title, profile cards and buttons are labelled and work from the keyboard. | `fix/profile-picker-first-run` |

## Trakt

| Change | What it does | Branch |
|---|---|---|
| Token refresh | Fixes a case where switching profiles while a Trakt token was being refreshed lost the new token and signed that profile out of Trakt. | `pr/a-trakt-refresh-token` |
| Offline queue | Watches recorded while offline are kept and sent when the connection returns. | `pr/e-trakt-offline-queue` |
| Queue visible | Settings, Tracking shows how many watches are waiting, with Sync now, and warns before disconnecting with watches still unsent. | `fix/trakt-queue-visible` |

## Wording

User-facing English text uses one set of terms ("source", "Auto-download") and plainer language for errors and settings. Only English strings were changed; other languages fall back to their existing text. Branch: `fix/wording-and-feedback`.

## Testing

The desktop test suite on `downloads-suite` runs 2,962 tests. Three fail, and the same three fail on upstream `Dev` at `c8ee7621` without any of these changes:

- `DesktopWindowInsetsTest` (title bar clearance)
- `SettingsNavigationTest` (buffer options by platform and playback engine)
- `WatchProgressIdentityTest` (legacy payload)

Not covered: Android and iOS builds, Intel Macs, Linux and Windows, and screen-reader testing beyond what the semantics tests assert.

## Keeping up with upstream

The `Dev` branch of this fork mirrors upstream `Dev`. The feature branches are rebased or merged onto it as upstream moves.

## Licence

Same as upstream: GNU General Public License v3.0. See `LICENSE`.
