# Download folders and locations: design

Desktop only (macOS first). Android and iOS code paths are not touched. Each point states the decision, then the reason.

## What the code found
- A finished record keeps an absolute `localFileUri`. An unfinished one keeps none; its partial file is `<folder>/<fileName>.part`. Subtitles sit beside the video in `<video>.subtitles`.
- Only the desktop downloader, the held-files part of Move to Trash, and the free-space provider read the folder directly. The list is stored by `DesktopStorage` (temp file, atomic rename, `.bak` copy).
- Today a finished record whose file is not found becomes Failed with its address cleared on load. An unplugged drive would do exactly that, so the load, delete and delete-after-watching paths must learn "unreachable is not lost".

## 1. Locations, and how a record refers to its file
- A **location** is a folder the user chose (or the app folder). The device keeps a small registry: id, path, a marker id. The **default location** is where NEW downloads go (the "Download folder" setting). More than one location in use is normal.
- A record gets one new optional field, `locationId` (null = the app folder, which is every record written before this feature). `localFileUri` stays an absolute address, written as before. Reason: an older build ignores `locationId`, keeps the field when it writes the list back (the codec keeps unknown fields), and still plays everything by `localFileUri`. What an older build cannot do: if a location is unplugged it will mark those items failed. That is the one thing that cannot be made backward safe, and is said in the report.
- Finding a file: (1) `<location of the record>/<fileName>`, (2) `localFileUri`, (3) `<fileName>` in every other known location. A hit in (3) repairs the record. So a record that was not updated after a crash still finds its file. The record is a hint, never a single point of failure. File names carry the download id, so names do not collide.
- Result of finding: Found, Unreachable(location, reason) or Missing. Only Missing becomes Failed as before.

## 2. Where the setting lives
`nuvio_download_folders.properties` in the app data folder, through `DesktopStorage.store`, with plain keys (not `ProfileScopedKey`) like `nuvio_download_speed`. Per device and shared by every profile; not read by any sync code. The folders themselves are outside the app data folder, so a wipe of app data never reaches them.

## 3. Validation (plain message each)
Checked in this order: not a folder or blank; the drive is not connected (never created, a missing mount point is not recreated); does not exist and cannot be created; not writable (a small file is created, written and removed); inside the app's own data or cache folder (or containing it) other than the default app folder itself; inside the program folder (java home, app resources, the app bundle); is the Trash (`.Trash`, `.Trashes`, `$RECYCLE.BIN`, `~/.local/share/Trash`); overlaps a folder already used by downloads; not enough free space for what is about to move (skipped when the move stays on one volume, since a rename needs none).

## 4. Moving is not all or nothing
- At a change of folder the user chooses: **Move everything**, **Choose what to move...** or **Leave everything where it is**. "Choose" shows a checklist by show and movie (collapsible to episodes) with sizes, a running total and the free space of the destination. A selection that does not fit is refused (the confirm button is off, with the reason); a selection that fits with less than 5 percent spare gives a warning.
- The Downloads screen has **Move to...** on an item, on a show and on a multi-selection ("Select"). It opens the native folder picker (locations already in use are offered first as choices, with "Another folder...") and runs the same move. The default location is not changed by it.
- One engine, `DownloadMover`, runs every move: a list of entries (item, from, name, size) to one destination.

## 5. The safe move
1. Validate; for a change of the default, set it first (new downloads then go there, and nothing is lost if the move stops).
2. Pause the active downloads of the moved items (ids written to the journal) and block new transfers into the moved items.
3. Per file: same volume, then an atomic rename. Otherwise copy to `<name>.moving`, check size and the OpenSubtitles hash of head and tail against the source (and against the hash the record states when the record was verified by it), rename to `<name>`, move the subtitles folder the same way, update the record (location and address), and only then delete the original.
4. Partial files of moved unfinished items follow them (size checked, source size must not change during the copy).
5. Progress: per file bytes and overall files and bytes; an announcement per file, never per chunk. Cancel is checked per chunk; the current copy is removed, finished files stay, the rest is untouched. Every entry is atomic, so every stopping point is consistent.
6. Journal `download-folder-move.json` in the app data folder, written by temp file and atomic rename before the first byte moves and removed at the end. Recovery at start, per entry, by looking at the disk: destination complete and verified, original still there, then finish (delete the original); original there and destination partial or absent, then roll back (remove our `.moving` or destination copy, never the original); original gone and destination there, then done. Rule: an original is never deleted unless the destination was verified, a destination is never deleted unless the original is present with the same size. The paused downloads in the journal are resumed. Recovery never copies: it takes seconds.
7. Result per move: "12 items moved to <folder>, 1 could not be moved: <reason>".

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

## 13. Not built / limits
Old builds can mark items on an unplugged drive failed. The ask for a manual download is on the Downloads row, not in the details dialog. Moving to a drive that is removed mid-move is recovered by the journal and reported as failed entries.
