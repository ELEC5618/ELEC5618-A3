# Safe-Delete Save Vault — Handoff Document

**ELEC5618 Software Quality Engineering — Assignment 3**

Project: Shattered Pixel Dungeon v3.0.2
Improvement: Save Recovery (based on teacher list item #2, reframed as a self-proposed improvement)
ISO/IEC 25010 Quality Attribute: Reliability → Recoverability

---

## 1. Overview

### 1.1 Background

The original `Dungeon.deleteGame()` in Shattered Pixel Dungeon permanently destroys a player's save folder the moment they confirm a deletion (or when their hero dies, or when they reach the Amulet). There is no built-in mechanism to undo this. From a software quality perspective, this is a **Recoverability** failure under ISO/IEC 25010.

### 1.2 What we built

A soft-delete subsystem called the **Safe-Delete Save Vault** (class name: `SaveRecycleBin`). When the game deletes a save slot, the slot's full folder is first copied to a timestamped subdirectory inside a `recycle_bin/` location, **before** the original destructive cleanup runs. Up to five most-recent snapshots are retained (FIFO eviction). Snapshots can be inspected and restored via two Gradle tasks, without launching the game.

### 1.3 Quality Improvement Mapping

| Aspect | Value |
|---|---|
| ISO/IEC 25010 main attribute | Reliability |
| Sub-characteristic | Recoverability |
| Measurable improvement | Number of permanently-lost saves after deletion drops from 100% to 0% (within the retention window of 5). |
| Side benefits | Maintainability (single hook point in `Dungeon.deleteGame`), Operability (CLI-based recovery without launching the game). |

---

## 2. Deliverables Summary

### 2.1 New source files (2)

| File | Layer | Purpose |
|---|---|---|
| `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/utils/SaveRecycleBin.java` | core | Runtime archive/list/prune logic. Invoked from in-game code. |
| `desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/RecycleBinCLI.java` | desktop | Standalone CLI: list/restore recycled snapshots without launching the game. |

### 2.2 Modified source files (2)

| File | Change |
|---|---|
| `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java` | Added one `import` and one line in `deleteGame()` to call `SaveRecycleBin.archive(save)` before the original destructive logic. |
| `desktop/build.gradle` | Registered two Gradle tasks: `listSaves` and `restoreSave`. |

### 2.3 Configuration changes

None. No new dependencies, no new resources, no plugin updates.

### 2.4 Runtime artifacts created at use time

| Path (macOS) | Created when | Description |
|---|---|---|
| `~/Library/Application Support/Shattered Pixel Dungeon/recycle_bin/` | First time a save is deleted | Container directory for snapshots. |
| `~/Library/Application Support/Shattered Pixel Dungeon/recycle_bin/<yyyyMMdd-HHmmss>_slot<N>/` | Each deletion | One snapshot. Contains the original `game.dat` and any `depthX.dat` files. |

---

## 3. Detailed Code Changes

### 3.1 New file: `SaveRecycleBin.java`

**Location:** `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/utils/SaveRecycleBin.java`

**Public API:**

| Method | Purpose |
|---|---|
| `archive(int slot)` | Copy `game<slot>/` folder into a new timestamped entry inside `recycle_bin/`. Prunes oldest entries when count exceeds 5. Safe no-op if the slot folder does not exist. |
| `listEntries()` | Return all snapshot folder names, sorted oldest-first (by timestamp, which is the lexicographic prefix). |

**Internal behavior:**

- Reads source folder via the project's existing `FileUtils.getFileHandle()` and `FileHandle.list()`.
- Builds the destination path as `recycle_bin/<yyyyMMdd-HHmmss>_slot<N>/`.
- Creates the destination via `FileHandle.mkdirs()`.
- Copies children one-by-one (only files, not subdirectories) using `FileHandle.copyTo()`. The original folder is intentionally left intact so that the existing destructive cleanup in `Dungeon.deleteGame()` runs as before.
- After every successful archive, `prune()` is called to enforce the 5-entry cap.

**Constants:**

| Name | Value | Reason |
|---|---|---|
| `BIN_DIR` | `"recycle_bin"` | Directory name under the game's data folder. |
| `MAX_ENTRIES` | `5` | Matches the teacher's requirement exactly. |

**Why this design:**

- Single source of truth for all archiving. Other code only needs to know `archive(slot)`.
- The 5-entry cap is enforced at write time, so the bin never grows unbounded.
- The timestamp format `yyyyMMdd-HHmmss` is both human-readable and lexicographically sortable, so we can use natural string ordering instead of parsing dates.

### 3.2 Modified file: `Dungeon.java`

**Location:** `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java`

**Change 1 — Import (near line 89):**

```java
import com.shatteredpixel.shatteredpixeldungeon.utils.SaveRecycleBin;
```

**Change 2 — `deleteGame` method (around line 838):**

```java
public static void deleteGame( int save, boolean deleteLevels ) {

    // Archive the save folder into the recycle bin before destroying it,
    // so the player can recover recently deleted saves. (ISO 25010: Recoverability)
    SaveRecycleBin.archive( save );

    if (deleteLevels) {
        // ...existing destructive logic unchanged...
    }
    FileUtils.overwriteFile(GamesInProgress.gameFile(save), 1);
    GamesInProgress.delete( save );
}
```

**Why this hook point:**

`Dungeon.deleteGame()` is the single function used by **all five** in-game deletion code paths:

1. `AmuletScene` — when the hero wins the game and the run is wiped.
2. `Hero.die()` — on permadeath.
3. `WndGameInProgress` — when the player clicks the Erase button on a save slot in the main menu.
4. `SewerLevel` (the boss room reset path).
5. `StartScene` (slot management).

By hooking only this one function we cover every deletion path with zero changes to those five callers. This is a textbook example of the "single chokepoint" maintainability pattern.

### 3.3 New file: `RecycleBinCLI.java`

**Location:** `desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/RecycleBinCLI.java`

**Why a separate class instead of reusing `SaveRecycleBin`:**

`SaveRecycleBin` is called from inside a running game, where libGDX has been initialised and `FileUtils.getFileHandle()` works. Gradle tasks run in a plain JVM with no libGDX context, so we need a libGDX-free way to read the same `recycle_bin/` folder. `RecycleBinCLI` resolves the OS-specific data directory itself using `System.getProperty("os.name")` (mirroring `DesktopLauncher`) and uses `java.nio.file.Files` for IO.

**Supported commands:**

| Command | Behavior |
|---|---|
| `list` | Prints all snapshot names, oldest first. Reports "Recycle bin is empty" if the directory does not exist or contains no entries. |
| `restore <snapshotId> <slot>` | Copies every file from `recycle_bin/<snapshotId>/` into `game<slot>/`, overwriting if needed. Exits with non-zero on failure (missing snapshot, IO error). |

**Data directory resolution** (matches `DesktopLauncher.java`):

| OS | Path |
|---|---|
| Windows (XP) | `Application Data/.shatteredpixel/Shattered Pixel Dungeon/` |
| Windows (modern) | `AppData/Roaming/.shatteredpixel/Shattered Pixel Dungeon/` |
| macOS | `~/Library/Application Support/Shattered Pixel Dungeon/` |
| Linux | `$XDG_DATA_HOME/.shatteredpixel/shattered-pixel-dungeon/` (falls back to `~/.local/share/...`) |

### 3.4 Modified file: `desktop/build.gradle`

**New tasks:**

```groovy
task listSaves(type: JavaExec) {
    classpath = sourceSets.main.runtimeClasspath
    main = "com.shatteredpixel.shatteredpixeldungeon.desktop.RecycleBinCLI"
    args 'list'
}

task restoreSave(type: JavaExec) {
    classpath = sourceSets.main.runtimeClasspath
    main = "com.shatteredpixel.shatteredpixeldungeon.desktop.RecycleBinCLI"
    doFirst {
        if (!project.hasProperty('snapshotId') || !project.hasProperty('slot')) {
            throw new GradleException("Usage: ./gradlew restoreSave -PsnapshotId=<id> -Pslot=<n>")
        }
        args 'restore', project.property('snapshotId'), project.property('slot')
    }
}
```

Both tasks reuse the desktop runtime classpath, so they pick up everything that the game itself uses at runtime. The `doFirst` block ensures `restoreSave` fails fast with a clear usage message if the user forgets `-PsnapshotId` or `-Pslot`.

---

## 4. Build, Run, and Verify

### 4.1 Environment

| Item | Value |
|---|---|
| Project root | `/Users/lovelymeow/Documents/5618/Assignment/Assignment3/shattered-pixel-dungeon-3.0.2/` |
| JDK | OpenJDK 17 (works with 16 too) |
| OS used for verification | macOS |

Export the JDK before any Gradle command:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

### 4.2 Build the project

```bash
cd /Users/lovelymeow/Documents/5618/Assignment/Assignment3/shattered-pixel-dungeon-3.0.2
./gradlew desktop:compileJava
```

Expected: `BUILD SUCCESSFUL`. (If Gradle complains about `gdx-controllers-core:2.2.4-SNAPSHOT`, change the version in `gradle.properties` to `2.2.4` per the PDF instructions.)

### 4.3 Run the game

```bash
./gradlew desktop:debug
```

### 4.4 End-to-end verification scenarios

#### Scenario A — Archive on delete (Verified ✅)

1. Launch the game.
2. Create a new save (any class, any progress).
3. Return to main menu, click the save, click Erase, confirm.
4. Quit the game completely.
5. Run `./gradlew listSaves`.

**Expected output (matches what we observed):**

```
Recycle bin entries (oldest first):
  20260506-173743_slot1
BUILD SUCCESSFUL
```

The corresponding folder on disk:

```
~/Library/Application Support/Shattered Pixel Dungeon/recycle_bin/20260506-173743_slot1/
├── game.dat
└── depth1.dat
```

#### Scenario B — Restore (Pending)

1. With at least one snapshot present, run:

```bash
./gradlew restoreSave -PsnapshotId=20260506-173743_slot1 -Pslot=1
```

2. Expected stdout: `Restored 20260506-173743_slot1 into slot 1`.
3. Launch the game. Slot 1 should reappear in the main menu with the original character.

#### Scenario C — FIFO eviction at >5 (Pending)

1. Repeat the create-and-delete cycle six times.
2. Run `./gradlew listSaves`.
3. Expected: exactly five entries, the earliest one (oldest timestamp) should be gone.

---

## 5. Task Breakdown Between Pair Members

| Area | Owner |
|---|---|
| `SaveRecycleBin.java` (core archive logic) | **Member A (already done)** |
| Hook in `Dungeon.deleteGame` | **Member A (already done)** |
| `RecycleBinCLI.java` initial version | **Member A (already done)** |
| Gradle tasks `listSaves` / `restoreSave` | **Member A (already done)** |
| End-to-end manual verification (Scenario A) | **Member A (done)** |
| `purge` command in `RecycleBinCLI` + Gradle task `purgeSaves` | **Member B** |
| 4-5 JUnit unit tests for `SaveRecycleBin` | **Member B** |
| End-to-end manual verification (Scenarios B and C) | **Member B** |
| SQA section of the demonstration video | **Member B** |
| Implementation section of the demonstration video | **Member A** |

---

## 6. Member B — Detailed Task List

### 6.1 Task — Add a `purge` command (estimated 1 hour)

**Goal:** Allow the user to wipe the entire recycle bin with one Gradle command. This is the natural third operation alongside `list` and `restore`.

**Step 1.** Open `desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/RecycleBinCLI.java`.

**Step 2.** Add a new case to the `switch` in `main`:

```java
case "purge":
    doPurge(bin);
    break;
```

**Step 3.** Add a method `doPurge(Path bin)`:

```java
private static void doPurge(Path bin) throws IOException {
    if (!Files.isDirectory(bin)) {
        System.out.println("Recycle bin is already empty.");
        return;
    }
    int removed = 0;
    try (Stream<Path> entries = Files.list(bin)) {
        for (Path entry : entries.collect(java.util.stream.Collectors.toList())) {
            deleteRecursive(entry);
            removed++;
        }
    }
    System.out.println("Purged " + removed + " snapshot(s).");
}

private static void deleteRecursive(Path p) throws IOException {
    if (Files.isDirectory(p)) {
        try (Stream<Path> children = Files.list(p)) {
            for (Path c : children.collect(java.util.stream.Collectors.toList())) {
                deleteRecursive(c);
            }
        }
    }
    Files.deleteIfExists(p);
}
```

**Step 4.** Update `printUsage` to advertise the new command:

```java
System.out.println("  purge");
```

**Step 5.** Add the matching Gradle task in `desktop/build.gradle` (next to the existing tasks):

```groovy
task purgeSaves(type: JavaExec) {
    classpath = sourceSets.main.runtimeClasspath
    main = "com.shatteredpixel.shatteredpixeldungeon.desktop.RecycleBinCLI"
    args 'purge'
}
```

**Step 6.** Verify:

```bash
./gradlew listSaves        # confirm bin has entries
./gradlew purgeSaves       # purge
./gradlew listSaves        # confirm now empty
```

### 6.2 Task — JUnit unit tests (estimated 3-4 hours)

**Goal:** Demonstrate SQA practice by writing tests that link directly to the improvement.

**Where to put tests:** Create `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/utils/SaveRecycleBinTest.java`. If the `test/` folder does not exist, create it. The project already supports JUnit through Gradle.

**Required dependency** — if missing, add to `core/build.gradle`:

```groovy
testImplementation 'junit:junit:4.13.2'
```

**Test 1 — `archive_movesGameFolderIntoBin`**
- Setup: write fake `game.dat` and `depth1.dat` into a temp directory representing `game1/`.
- Action: call `SaveRecycleBin.archive(1)`.
- Assert: a folder matching `recycle_bin/*slot1/` exists and contains the same files with identical bytes.

**Test 2 — `archive_capsAtFiveEntries_evictsOldest`**
- Setup: create six fake save folders, archive each in order with a one-second sleep between to ensure distinct timestamps.
- Action: call `archive` six times.
- Assert: `listEntries()` returns exactly five entries; the earliest timestamp is no longer present.

**Test 3 — `archive_whenSlotMissing_doesNothing`**
- Setup: do not create any save folder for slot 99.
- Action: call `SaveRecycleBin.archive(99)`.
- Assert: no exception is thrown, and `recycle_bin/` either does not exist or contains zero entries.

**Test 4 — `restore_recreatesAllFiles`**
- Setup: archive one slot. Then delete the original `game1/` to simulate the destructive cleanup.
- Action: use `RecycleBinCLI.main(new String[]{"restore", "<id>", "1"})` (or the same logic factored into a method).
- Assert: every file from the archive exists at the restored location with the same bytes.

**Test 5 — `listEntries_returnsSortedOldestFirst`**
- Setup: archive three slots, sleeping between calls.
- Action: call `SaveRecycleBin.listEntries()`.
- Assert: the returned list is in chronological order (oldest first).

**Run tests:**

```bash
./gradlew core:test
```

Capture the green "OK" output as a screenshot for the video.

**Note on libGDX in tests:**

`SaveRecycleBin` calls `FileUtils.getFileHandle()` which uses `Gdx.files`. In a unit-test context `Gdx.files` is null. Two ways to handle this:

- **Option A (simpler):** before each test, initialise a `HeadlessApplication` from libGDX so `Gdx.files` works. Add `testImplementation "com.badlogicgames.gdx:gdx-backend-headless:$gdxVersion"` to `core/build.gradle`.
- **Option B (cleaner):** refactor `SaveRecycleBin` so the file IO is behind a small interface, and inject a fake implementation in tests. More work, but no headless dependency.

Recommended: Option A. It is faster to set up and the tests still exercise the real code path.

### 6.3 Task — End-to-end manual verification

Follow Scenarios B and C in section 4.4 above. Record terminal output (text or screenshot) for the video.

### 6.4 Task — Demonstration video (your half)

Suggested 1.5-minute structure for the SQA / verification portion:

1. **0:00–0:20** — Show `RecycleBinCLI.java` on screen, point at the `purge` block you added.
2. **0:20–0:50** — Show the JUnit test file, then run `./gradlew core:test` and pause on the green output.
3. **0:50–1:20** — Run `./gradlew listSaves`, then `./gradlew restoreSave -PsnapshotId=... -Pslot=1`, then re-launch the game to show the restored save in the main menu.
4. **1:20–1:30** — Run `./gradlew purgeSaves`, then `./gradlew listSaves` to show the bin is empty.

---

## 7. Naming Convention

For consistency across artifacts:

| Context | Term |
|---|---|
| Code class | `SaveRecycleBin` |
| Public-facing name (PPT, video title, README) | **Safe-Delete Save Vault** |
| Runtime directory | `recycle_bin/` |
| Gradle tasks | `listSaves`, `restoreSave`, `purgeSaves` |
| Snapshot folder pattern | `yyyyMMdd-HHmmss_slot<N>` |

---

## 8. Comparison Against Teacher's List Item #2

The teacher's prompt:

> Enable recovery of up to 5 most recently deleted game saves. Deleted saves should be moved to a designated recovery location instead of being permanently removed. Recovery through a command/script/Gradle custom task is acceptable. UI changes are optional and not required.

Our implementation maps to each requirement as follows:

| Teacher requirement | Our implementation | Status |
|---|---|---|
| Up to 5 most recently deleted saves | `SaveRecycleBin.MAX_ENTRIES = 5` with FIFO eviction in `prune()` | Met |
| Move to a designated recovery location instead of permanent removal | Folder is copied to `recycle_bin/<timestamp>_slot<N>/` before the original cleanup runs | Met |
| Recovery via command / script / Gradle custom task | `./gradlew listSaves` and `./gradlew restoreSave` (plus optional `./gradlew purgeSaves`) | Met |
| UI changes optional | We made zero UI changes | Met |

**Differences from the literal prompt** (reasons for our chosen framing):

1. Naming: we use **Safe-Delete Save Vault** rather than "save recovery". This name signals a deliberate Recoverability subsystem rather than an ad-hoc undo.
2. Two commands instead of one: we provide `listSaves` separately from `restoreSave`. This matches normal human workflow (inspect, then restore) and is friendlier on the command line.
3. Explicit ISO/IEC 25010 mapping: we declare the change is targeting Reliability → Recoverability. The original prompt does not require this but the assignment rubric does.
4. Single chokepoint design: we hook only `Dungeon.deleteGame()` rather than touching the five call sites. This is a maintainability decision not implied by the original prompt.

---

## 9. Out of Scope (Intentionally Not Implemented)

These extensions are common in similar systems but were not part of our scope to keep the implementation focused. They can be added later if needed.

| Item | Reason for exclusion |
|---|---|
| Per-snapshot metadata file (slot, class, depth, HP, timestamp in human form) | Not required by the rubric. Adds complexity. |
| Time-based retention (e.g. auto-delete after 30 days) | Out of scope. |
| In-game UI for browsing and restoring snapshots | Explicitly marked optional in the original prompt. |
| Encryption of archived saves | Not a stated requirement and unnecessary for a single-player roguelike. |

---

## 10. Quick Reference Card

```text
# Build
./gradlew desktop:compileJava

# Run the game
./gradlew desktop:debug

# List snapshots
./gradlew listSaves

# Restore a specific snapshot into slot N
./gradlew restoreSave -PsnapshotId=<id> -Pslot=<N>

# Purge all snapshots (after Member B adds this task)
./gradlew purgeSaves

# Run unit tests (after Member B adds them)
./gradlew core:test

# macOS data directory
~/Library/Application\ Support/Shattered\ Pixel\ Dungeon/

# Recycle bin (created on first delete)
~/Library/Application\ Support/Shattered\ Pixel\ Dungeon/recycle_bin/
```

---

*End of handoff document.*
