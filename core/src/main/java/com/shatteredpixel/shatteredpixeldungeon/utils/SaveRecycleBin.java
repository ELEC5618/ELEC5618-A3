/*
 * SaveRecycleBin
 *
 * Quality improvement: Reliability / Recoverability (ISO/IEC 25010).
 *
 * Before a save slot is destroyed by Dungeon.deleteGame(...), this class
 * archives the slot's folder into recycle_bin/<timestamp>_slot<N>/ so the
 * player can recover up to MAX_ENTRIES most recently deleted saves.
 * When the bin overflows, the oldest entry is permanently removed (FIFO).
 */
package com.shatteredpixel.shatteredpixeldungeon.utils;

import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.watabou.utils.FileUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class SaveRecycleBin {

	public static final String BIN_DIR = "recycle_bin";
	public static final int MAX_ENTRIES = 5;

	private static final SimpleDateFormat STAMP_FMT =
			new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);

	/**
	 * Archive the given slot's folder into the recycle bin. Called from
	 * Dungeon.deleteGame() before the destructive cleanup runs.
	 * Silently no-ops if the slot folder does not exist.
	 */
	public static void archive(int slot) {
		String srcName = GamesInProgress.gameFolder(slot);
		if (!FileUtils.dirExists(srcName)) return;

		ensureBinExists();

		String entryName = BIN_DIR + "/" + STAMP_FMT.format(new Date())
				+ "_slot" + slot;

		FileHandle src = FileUtils.getFileHandle(srcName);
		FileHandle dst = FileUtils.getFileHandle(entryName);

		// Copy children one-by-one so we leave the original folder
		// structure for Dungeon.deleteGame() to finish wiping.
		dst.mkdirs();
		for (FileHandle child : src.list()) {
			if (!child.isDirectory()) {
				child.copyTo(dst.child(child.name()));
			}
		}

		prune();
	}

	/** List recycle bin entries, oldest first. */
	public static ArrayList<String> listEntries() {
		ArrayList<String> entries = new ArrayList<>();
		if (!FileUtils.dirExists(BIN_DIR)) return entries;
		for (String name : FileUtils.filesInDir(BIN_DIR)) {
			entries.add(name);
		}
		Collections.sort(entries);
		return entries;
	}

	/** Drop oldest entries until size <= MAX_ENTRIES. */
	private static void prune() {
		ArrayList<String> entries = listEntries();
		while (entries.size() > MAX_ENTRIES) {
			String oldest = entries.remove(0);
			FileUtils.deleteDir(BIN_DIR + "/" + oldest);
		}
	}

	private static void ensureBinExists() {
		if (!FileUtils.dirExists(BIN_DIR)) {
			FileUtils.getFileHandle(BIN_DIR).mkdirs();
		}
	}
}
