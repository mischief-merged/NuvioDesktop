# Download folders and locations: design

Desktop only (macOS first). Android and iOS code paths are not touched. Each point states the decision, then the reason.

## What the code found
- A finished record keeps an absolute `localFileUri`. An unfinished one keeps none; its partial file is `<folder>/<fileName>.part`. Subtitles sit beside the video in `<video>.subtitles`.
- Only the desktop downloader, the held-files part of Move to Trash, and the free-space provider read the folder directly. The list is stored by `DesktopStorage` (temp file, atomic rename, `.bak` copy).
- Today a finished record whose file is not found becomes Failed with its address cleared on load. An unplugged drive would do exactly that, so the load, delete and delete-after-watching paths must learn "unreachable is not lost".

## 1. Locations, and how a record refers to its file
- A **location** is a folder the user chose (or the app folder). The device keeps a small registry: id, path, a marker id. The **default location** is where NEW downloads go (the "Download folder" setting). More than one location in use is normal.
- A record gets one new optional field, `locationId` (null = the app folder, which is every record written before this feature). `localFileUri` stays an absolute address, written as before. Reason: an older build ignores `locationId`, keeps the field when it writes the list back (the codec keeps unknown fields), and still plays everything by `localFileUri`. What an older build cannot do: if a location is unplugged it will mark those items failed. That is the one thing that cannot be made backward safe, and is said in the report.
- Finding a file: only in the place the record names. (1) the record's own location must be available (drive there, folder there, its marker holding its id); if it is not, the answer is "Drive not connected" (or "Folder not found", or "not set up") and NOTHING else is searched. (2) Inside it, `<location>/<fileName>` or the recorded address; a recorded address inside another location counts only if that location passes the same check. A file of the same name in another location is another file and is never adopted: searching by name is not a repair mechanism. A record is pointed somewhere else only by a journalled move (or, after a crash, by that journal).
- Result of finding: Found, Unreachable(location, reason) or Missing. Only Missing becomes Failed as before.

## 2. Where the setting lives
`nuvio_download_folders.properties` in the app data folder, through `DesktopStorage.store`, with plain keys (not `ProfileScopedKey`) like `nuvio_download_speed`. Per device and shared by every profile; not read by any sync code. The folders themselves are outside the app data folder, so a wipe of app data never reaches them.

## 3. Validation (plain message each)
Checked in this order: not a folder or blank; the drive is not connected (never created, a missing mount point is not recreated); does not exist and cannot be created; not writable (a small file is created, written and removed); inside the app's own data or cache folder (or containing it) other than the default app folder itself; inside the program folder (java home, app resources, the app bundle); is the Trash (`.Trash`, `.Trashes`, `$RECYCLE.BIN`, `~/.local/share/Trash`); overlaps a folder already used by downloads; not enough free space for what is about to move (skipped when the move stays on one volume, since a rename needs none).

## 4. Moving is not all or nothing
- At a change of folder the user chooses: **Move everything**, **Choose what to move...** or **Leave everything where it is**. "Choose" shows a checklist by show and movie (collapsible to episodes) with sizes, a running total and the free space of the destination. A selection that does not fit is refused (the confirm button is off, with the reason); a selection that fits with less than 5 percent spare gives a warning.
- The Downloads screen has **Move to...** on an item, on a show and on a multi-selection ("Select"). It opens the native folder picker (locations already in use are offered first as choices, with "Another folder...") and runs the same move. The default location is not changed by it.
- One engine, `DownloadMover`, runs every move: a list of entries (item, from, name, size) to one destination.

## 5. The safe move: one transaction
The rule: the code deletes or replaces only a file it can prove it made in this transaction, and deletes an original only after a copy hashed whole has been published, read back, and the record durably saved. When proof is missing it stops, leaves everything as it is, and says so.

Before anything: the move takes a per-item lock (`ItemLocks`, never waits: an item another thing has is reported "busy"), skips an item that is playing (reported), and for a running download pauses it and WAITS until its transfer has stopped writing and closed its file (reported "did not stop in time" if it does not). Only then are sizes and hashes taken. The same lock is taken by manual delete, delete-after-watching, file checks and transfer start, so none can run on an item a move has; its row says "Moving…" and Delete is off. The lock is held until the record is updated and released at the end; paused downloads then resume from the new place.

Per download (a video with its companions, a partial file, or a record-only item), in this order, each step written to the journal (file flushed, then its folder) before the next:
1. planned: names in the destination are reserved (a name that exists in any letter case, or that two items of the move would share, fails that item as "a file with that name is already there"); a link is never moved (reported); temporary names `.nuvio-<txid>-<n>.tmp` are reserved in the journal.
2. copying (state written first), to the unique temp name; on one volume a hard link is used instead and nothing is copied. SHA-256 of the whole file is taken while it is read; the temp file is flushed.
3. copied: size and modification time of the original must be unchanged; the hash is journalled.
4. published: a hard link from the temp name to the final name, which fails if the name exists (where hard links are unsupported, a rename that does not replace; the remaining check-then-rename window is stated, and a name that appears is a failure for that item). The published file is read back once, whole, and its hash compared with the journalled one; then the destination folder is flushed, `published` is journalled and the temp name is removed. A copy that does not match is deleted (it is ours) and the original stays.
5. record: `updating` is journalled, the hook confirms the record still exists and still points where the move found the file (and the profile is the same), the record is changed and saved with flushing, `updated` is journalled.
6. original removal: delete, flush the source folder, `removed` journalled. If the original cannot be removed (locked, permission) the item counts as moved, the result says "moved; the original could not be removed: <reason>", the journal keeps it, and it is retried at every start. Where folders cannot be flushed on the platform, the original is kept until the next start has confirmed the destination by hash (two-phase removal).
Companions: every file that shares the video's base name in its folder, and every file in a folder whose name starts with that base name, is its own journal entry with its own hash and goes through steps 2 to 4 with the video; no original is removed until all of the item are published and the record is saved. One rule, in `DownloadCompanions`: a companion of `base.ext` is a file in the same folder whose name starts with `base.`, or a folder so named with everything in it; never `.part`, `.moving`, `.tmp` or `.nuvio-` names; never a link (skipped, reported). A future skip-times file named `base.skiptimes.json` travels without changes.
Any failure before step 5 rolls the item back: the copies this transaction published (provable: it published them and read them back, and the original is still there) and its own temp files are removed. A full destination fails that item, removes its temp file, and stops the move (the rest are reported as not reached). Cancel stops after the current chunk and removes its temp file; finished items stay moved.

Recovery at start works only from the journal. A temp file with this transaction's name may be removed. A file at a final name is this transaction's only if the journal recorded its publication and it matches (same file as the original for a link; same hash for a copy). Items whose record is `pending`: removed what is proven ours, originals untouched. Items whose record is `updated`: originals removed beside proven copies. Items `updating` cannot be told from before or after the record changed: nothing is touched until the list is loaded; then, if the record is at the destination the originals are removed; if it is still at the original, it is pointed to the published file from the journal (this is also how a moved `.part` makes its download resume instead of restarting) and the originals are removed; if the record is gone, everything is left and reported. A destination file the journal does not prove is never touched, and the original beside it is never removed.

## 6. Shows stay together
Rule: a new episode of a show (manual, season, automatic) goes to the location holding that show's downloads (the location of the most recent finished or running item with the same show id), otherwise to the default location. Moving the show is how it changes. If that location is not connected: automatic downloads for the show wait with a visible reason ("Drive not connected"); a manual download is queued waiting at that location and its row offers "Choose where to save", so nothing is put elsewhere silently. (The details screens call enqueue and have no way to ask; the question is asked on the Downloads screen row.)

## 7. Unplugged drives, per location
- Availability is computed per location from: the volume's mount point exists (macOS `/Volumes/X`, a Windows drive root, Linux `/media/..`, `/run/media/..`, `/mnt/..`), the folder exists and holds the location's marker file. The marker stops a path that something recreated on the system disk from passing as the drive.
- A background check every 3 seconds on its own thread (a hung network mount cannot freeze the interface) publishes the state. Row state is a pure lookup.
- Unreachable items stay Completed in the list, so nothing is purged, nothing is re-downloaded and auto-download counts them as kept. The row says "Drive not connected" (or "Folder not found" when the drive is there and the folder is not). Delete and delete-after-watching on such an item do nothing and say why. A download whose location is unreachable pauses itself with `pausedByFolder` (stored, so it survives a restart); when the location returns it resumes itself. Auto-download checks and delete-after-watching wait for the affected rules or items; the Downloads screen shows a banner naming each unreachable location.

## 8. Space and limits
- Free space is read on the destination volume of each download (its location), including the check made before a transfer writes and the minimum free space of automatic downloads.
- The automatic-download storage limit counts the recorded bytes of automatic downloads in connected locations. A disconnected location is not counted, as the request says; when it returns the total may be above the limit. Nothing in the app deletes to make room: the limit only stops or delays new automatic downloads, and delete-after-watching is time and watch based. So an unplugged drive cannot cause deletion anywhere.

## 9. Move to Trash
`Desktop.moveToTrash` is per volume on macOS. The held-files fallback lives inside the item's own location. If the move fails the screen says so and offers "Delete permanently", which needs its own confirmation naming the file and saying it cannot be undone.

## 10. Native folder picker
macOS: `java.awt.FileDialog` with `apple.awt.fileDialogForDirectories` (read by `CFileDialog$Task` in the JDK 17 used here, checked with `javap`). The property is process wide, so it is set and cleared under a lock around the call. Windows and Linux: a Swing chooser in directory mode. Not run: nothing here opens a real dialog.

## 11. Accessibility
Every control has a role and a label; the setting row reads title, folder, free space and state as one item; locations are list items with their state; progress uses `progressBarRangeInfo` and a polite live region for per-file and result announcements; the checklist rows are checkboxes with state; every dialog and button works from the keyboard.

## 12. Details settled while building
- Locations that hold no items and are not the default are forgotten from the registry, but only after a move that ended normally; after a crash or an error the destination stays known so files already there are still found. Forgetting does not touch the folder on disk. A forgotten folder that is chosen again gets a new id and marker.
- The saved list of locations is read once and again only after this code changes it, because it is asked for by every row.
- A download whose show is in a location that is away is queued waiting (paused, `pausedByFolder`) and the row offers "Choose where to save"; choosing a location discards any partial file at the old place (it was never reachable) and starts the download there.
- When moving to the Trash fails the toast says so and a second dialog offers "Delete permanently".

## 13. Profiles
The list is per profile; locations are per device. A move belongs to the profile that started it: the hooks it uses (list, pause, relocate, resume) are the active profile's, switching profile is refused while a move runs ("Wait for the move to finish before switching profile"), and if the profile changed anyway the move stops after the current item. A location is forgotten only when no record of any profile's stored list uses it (every profile's stored list is read, not only the active one).

## 14. Drives, time limits and threads
Every file-system call that may hang on a failing drive or network volume runs on a helper thread with a time limit (`FsGuard`); a volume that does not answer counts as not connected and nothing is touched. The Downloads screen and the setting read the state the monitor publishes and run their own refresh and free-space reads off the interface thread. A healthy drive whose marker is missing is "not set up: choose it again", never lost items. A drive that mounts at another path is found by the id its marker holds (never by file names) and the saved path is updated.

## 15. Downloader speed
Before: reads of the JVM default 8 KiB, and `onProgress` on every read, which reached `DownloadsRepository`, which published the state and rewrote the whole stored list under the global lock each time. Now: reads of 256 KiB, capped to a tenth of a low speed limit per second so the wait is spread smoothly; progress is reported at most every 250 ms and once more, exactly, when the transfer ends or fails; cancelling closes the response body and interrupts the request, so a blocked read ends at once and a move can wait for the writer to have finished.

## 16. Not built / limits
Old builds can mark items on an unplugged drive failed. The ask for a manual download is on the Downloads row, not in the details dialog. Moving to a drive that is removed mid-move is recovered by the journal and reported as failed entries.
