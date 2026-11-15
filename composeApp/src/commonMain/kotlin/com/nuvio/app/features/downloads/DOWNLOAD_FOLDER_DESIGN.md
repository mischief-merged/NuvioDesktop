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

## 5. The move: eight rules, and a duplicate is always better than a deletion
Patching holes did not converge, so the design is small. Where it is in doubt it keeps both copies and says so.

1. **Recovery never deletes or overwrites a user's file.** Only the live move, in its own process, removes an original, and only as its very last step, after ALL of: a copy verified by full SHA-256 is published at the final name; the destination folder flush succeeded; the record was durably saved pointing at the new place; the journal recorded that. If a barrier failed, is unsupported on the volume, or threw, the original stays and the item is reported "kept in both places". Once a final name exists nothing the move created there is ever deleted automatically, live or in recovery. After a crash recovery does exactly two things: (a) removes temporary files this transaction created, identified by the journal, by the transaction's random id in the name and by the file identity recorded at creation, and only after the location's marker validated; (b) finishes a record update, only if the journal proves the destination was published and its hash matches and the record still matches the journalled identity. Everything else is left and shown: Settings, Storage locations, "Leftover copies" lists each file that exists in two places (or is untracked) with its size and both paths and two buttons, "Remove the extra copy" (re-checks equal content hash, or the same file for a link, and that the record points at the copy being kept, immediately before deleting) and "Keep both". Nothing there happens without a press.
2. **One transaction, one journal, one process.** Each move has its own journal file `move-<128-bit id>.journal`, created without overwriting; updates are written to a transaction-specific temp, flushed, and published by rename. A journal write that fails stops the move at that point under rule 1. A new move is refused while any unresolved journal exists ("Finish the previous move first"). An OS lock (`FileChannel.tryLock` on a lock file in the app data folder) guards moves and recovery, so a second window or process is refused ("A move is in progress in another window"). The journal stores per file: owning profile, item id, the record's stored location id and relative path at plan time (the identity), source and destination as location id plus relative path, size, full hash, the source's file identity, and each step reached. Recovery resolves locations through their markers; a location that is away, or whose marker does not match, is not touched, and its entries stay pending.
3. **Records are updated only against the exact record planned for:** same profile, same item id, still pointing at the journalled source. Anything else means no update and the published copy is a leftover.
4. **Exactly one writer per item, enforced.** A per-item writer token is acquired atomically when a transfer starts and released only after the transfer closed its file. Pause and cancel request the stop; the token frees when the writer really exits. Resume waits for it. A move takes the same token for every item for the whole transaction; if it cannot within a limit the item is skipped and reported, and a growing file is never copied. The item lock used by delete, delete-after-watching, cancel, clear-failed and file checks is acquired by them, not looked at.
5. **No adoption by name, anywhere.** A file is found only where its record says, in that record's location, validated by the marker. No search of other locations for a `.part` or anything else.
6. **Companions are only** the item's own partial file and the contents of its dedicated sidecar folder `<video file name>.subtitles/` (the one place for subtitles, skip times, episode lists and artwork). Never sibling files that share a base name. A path owned by another record stops that item with a report.
7. **Temporary and probe files** have random-id names, are created without overwriting, are remembered as ours only after creation succeeded, and are deleted only after their identity still matches; a taken name is left alone and another is chosen.
8. **Publication never replaces:** a hard link to the final name; where links are unsupported, a copy straight to the final name created without overwriting. An existing final name fails that entry. A move inside one volume (link) follows rules 1 to 4 too; the file is then the same one, so its check is "same file", not a re-read.

Live steps per item: take locks and tokens; plan (identity, source file identity, companions, ownership); per file create the temp, journal it, copy with hash, journal the hash; publish, read back, flush the destination folder, journal published; check the record against the identity; save the record durably; journal recorded; remove the original only if its identity is still the journalled one, flush its folder. Any failure before the record is saved keeps both copies; a temp file of ours is removed, a copy at a final name is kept and listed.

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
