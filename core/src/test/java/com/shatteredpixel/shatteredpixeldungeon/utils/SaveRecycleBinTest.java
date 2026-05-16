/*
 * SaveRecycleBinTest
 *
 * SQA verification suite for the Safe-Delete Save Vault improvement.
 * Each test maps to one observable property of the recycle bin behavior:
 *
 *   1. archive_movesGameFolderIntoBin
 *      The folder of a deleted slot must appear under recycle_bin/.
 *
 *   2. archive_preservesFileBytes
 *      Files inside an archived snapshot must be byte-identical to the
 *      originals (Recoverability requires the snapshot to be lossless).
 *
 *   3. archive_capsAtFiveEntries_evictsOldest
 *      The recycle bin must never hold more than MAX_ENTRIES snapshots;
 *      when the cap is exceeded, the oldest one is removed (FIFO).
 *
 *   4. archive_whenSlotMissing_doesNothing
 *      Archiving a slot whose folder does not exist must be a safe no-op,
 *      never throw, never leave artefacts behind.
 *
 *   5. listEntries_returnsSortedOldestFirst
 *      listEntries() is the read side that drives both the UI message and
 *      the FIFO eviction, so its ordering must be deterministic.
 *
 * Tests use libGDX HeadlessFiles so SaveRecycleBin's real FileHandle paths
 * are exercised against a temp directory instead of the player's home dir.
 */
package com.shatteredpixel.shatteredpixeldungeon.utils;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.watabou.utils.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SaveRecycleBinTest {

	private Path tempDir;

	@BeforeClass
	public static void initLibGdx() {
		// Start a Headless libGDX application once for the test class so that
		// Gdx.app / Gdx.files are available. Messages.<clinit> reaches into
		// Gdx.app.getPreferences(...) and would NPE without this.
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		// Disable the render loop; we only need the static fields populated.
		config.updatesPerSecond = -1;
		new HeadlessApplication(new ApplicationAdapter() {}, config);
	}

	@Before
	public void setUp() throws IOException {
		// Each test runs against a fresh temp directory so we never touch
		// the player's real save folder.
		tempDir = java.nio.file.Files.createTempDirectory("svault-test-");
		FileUtils.setDefaultFileProperties(
				Files.FileType.Absolute,
				tempDir.toAbsolutePath().toString() + File.separator);
	}

	@After
	public void tearDown() throws IOException {
		if (tempDir != null) {
			deleteRecursively(tempDir.toFile());
		}
	}

	// --- Test 1 ----------------------------------------------------------

	@Test
	public void archive_movesGameFolderIntoBin() throws IOException {
		writeGameFolder(1, "hero contents", "level contents");

		SaveRecycleBin.archive(1);

		File binDir = new File(tempDir.toFile(), SaveRecycleBin.BIN_DIR);
		assertTrue("recycle_bin/ should be created on first archive",
				binDir.isDirectory());

		File[] entries = binDir.listFiles(File::isDirectory);
		assertNotNull(entries);
		assertEquals("exactly one snapshot should exist", 1, entries.length);
		assertTrue("snapshot folder name should end with _slot1, was: " + entries[0].getName(),
				entries[0].getName().endsWith("_slot1"));

		File snapshotGame = new File(entries[0], "game.dat");
		File snapshotDepth = new File(entries[0], "depth1.dat");
		assertTrue("snapshot must contain game.dat", snapshotGame.isFile());
		assertTrue("snapshot must contain depth1.dat", snapshotDepth.isFile());
	}

	// --- Test 2 ----------------------------------------------------------

	@Test
	public void archive_preservesFileBytes() throws IOException {
		byte[] gameBytes = "the original hero bytes 0xCAFEBABE".getBytes();
		byte[] depthBytes = "the original level bytes 0xDEADBEEF".getBytes();

		writeFile(slotFolder(2), "game.dat", gameBytes);
		writeFile(slotFolder(2), "depth1.dat", depthBytes);

		SaveRecycleBin.archive(2);

		File snapshot = onlySnapshot();
		assertArrayEquals("archived game.dat must be byte-identical",
				gameBytes, readFile(snapshot, "game.dat"));
		assertArrayEquals("archived depth1.dat must be byte-identical",
				depthBytes, readFile(snapshot, "depth1.dat"));
	}

	// --- Test 3 ----------------------------------------------------------

	@Test
	public void archive_capsAtFiveEntries_evictsOldest() throws IOException {
		// Archive six distinct slots; with default 1-second timestamp precision
		// the FIFO order is determined by lexicographic name (slot1 first).
		for (int slot = 1; slot <= 6; slot++) {
			writeGameFolder(slot, "hero-" + slot, "level-" + slot);
			SaveRecycleBin.archive(slot);
		}

		ArrayList<String> remaining = SaveRecycleBin.listEntries();
		assertEquals("bin must keep at most " + SaveRecycleBin.MAX_ENTRIES + " entries",
				SaveRecycleBin.MAX_ENTRIES, remaining.size());

		// The oldest (slot1) must have been evicted; the rest must survive.
		for (String name : remaining) {
			assertFalse("oldest entry slot1 must be evicted, but found: " + name,
					name.endsWith("_slot1"));
		}
		for (int slot = 2; slot <= 6; slot++) {
			final int s = slot;
			boolean present = remaining.stream().anyMatch(n -> n.endsWith("_slot" + s));
			assertTrue("slot " + slot + " must still be present", present);
		}
	}

	// --- Test 4 ----------------------------------------------------------

	@Test
	public void archive_whenSlotMissing_doesNothing() throws IOException {
		// Slot 99 has no folder on disk.
		SaveRecycleBin.archive(99);

		File binDir = new File(tempDir.toFile(), SaveRecycleBin.BIN_DIR);
		if (binDir.exists()) {
			File[] entries = binDir.listFiles(File::isDirectory);
			assertNotNull(entries);
			assertEquals("no snapshot should be created for a missing slot",
					0, entries.length);
		}
	}

	// --- Test 5 ----------------------------------------------------------

	@Test
	public void listEntries_returnsSortedOldestFirst() throws IOException {
		// Hand-craft three entries with controlled timestamp prefixes so we
		// know the expected order independent of system clock granularity.
		File binDir = new File(tempDir.toFile(), SaveRecycleBin.BIN_DIR);
		assertTrue(binDir.mkdirs());
		assertTrue(new File(binDir, "20260101-000000_slot1").mkdirs());
		assertTrue(new File(binDir, "20260301-000000_slot3").mkdirs());
		assertTrue(new File(binDir, "20260201-000000_slot2").mkdirs());

		ArrayList<String> entries = SaveRecycleBin.listEntries();

		assertEquals(3, entries.size());
		assertEquals("20260101-000000_slot1", entries.get(0));
		assertEquals("20260201-000000_slot2", entries.get(1));
		assertEquals("20260301-000000_slot3", entries.get(2));
	}

	// --- helpers ---------------------------------------------------------

	private File slotFolder(int slot) {
		return new File(tempDir.toFile(), GamesInProgress.gameFolder(slot));
	}

	private void writeGameFolder(int slot, String gameContent, String depthContent) throws IOException {
		File f = slotFolder(slot);
		writeFile(f, "game.dat", gameContent.getBytes());
		writeFile(f, "depth1.dat", depthContent.getBytes());
	}

	private static void writeFile(File dir, String name, byte[] bytes) throws IOException {
		if (!dir.exists() && !dir.mkdirs()) {
			throw new IOException("Could not create " + dir);
		}
		java.nio.file.Files.write(new File(dir, name).toPath(), bytes);
	}

	private static byte[] readFile(File dir, String name) throws IOException {
		return java.nio.file.Files.readAllBytes(new File(dir, name).toPath());
	}

	private File onlySnapshot() {
		File binDir = new File(tempDir.toFile(), SaveRecycleBin.BIN_DIR);
		File[] entries = binDir.listFiles(File::isDirectory);
		assertNotNull(entries);
		assertEquals(1, entries.length);
		return entries[0];
	}

	private static void deleteRecursively(File f) {
		if (f == null || !f.exists()) return;
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children != null) {
				for (File c : children) deleteRecursively(c);
			}
		}
		f.delete();
	}
}
