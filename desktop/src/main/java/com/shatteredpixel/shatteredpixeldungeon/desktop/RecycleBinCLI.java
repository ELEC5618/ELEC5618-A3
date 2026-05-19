/*
 * Standalone command-line tool to list, restore and purge entries archived
 * by SaveRecycleBin. Designed to run from a Gradle task (no libgdx context).
 *
 * Usage:
 *   ./gradlew listSaves
 *   ./gradlew restoreSave -PsnapshotId=<entryName> -Pslot=<n>
 *   ./gradlew purgeSaves
 */
package com.shatteredpixel.shatteredpixeldungeon.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.stream.Stream;

public class RecycleBinCLI {

	private static final String TITLE = "Shattered Pixel Dungeon";
	private static final String VENDOR = "shatteredpixel";

	public static void main(String[] args) throws IOException {
		if (args.length == 0) { printUsage(); return; }

		Path dataDir = resolveDataDir();
		Path bin = dataDir.resolve("recycle_bin");

		switch (args[0]) {
			case "list":
				doList(bin);
				break;
			case "restore":
				if (args.length < 3) { printUsage(); System.exit(1); }
				doRestore(bin, dataDir, args[1], Integer.parseInt(args[2]));
				break;
			case "purge":
				doPurge(bin);
				break;
			default:
				printUsage();
				System.exit(1);
		}
	}

	private static void doList(Path bin) throws IOException {
		if (!Files.isDirectory(bin)) {
			System.out.println("Recycle bin is empty (no folder at " + bin + ")");
			return;
		}
		ArrayList<String> entries = new ArrayList<>();
		try (Stream<Path> s = Files.list(bin)) {
			s.filter(Files::isDirectory).forEach(p -> entries.add(p.getFileName().toString()));
		}
		Collections.sort(entries);
		if (entries.isEmpty()) {
			System.out.println("Recycle bin is empty.");
			return;
		}
		System.out.println("Recycle bin entries (oldest first):");
		for (String e : entries) System.out.println("  " + e);
	}

	private static void doRestore(Path bin, Path dataDir, String entryName, int slot) throws IOException {
		Path entry = bin.resolve(entryName);
		if (!Files.isDirectory(entry)) {
			System.err.println("No such snapshot: " + entryName);
			System.exit(1);
		}
		Path target = dataDir.resolve("game" + slot);
		Files.createDirectories(target);
		try (Stream<Path> s = Files.list(entry)) {
			s.forEach(src -> {
				try {
					Files.copy(src, target.resolve(src.getFileName()),
							StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
		System.out.println("Restored " + entryName + " into slot " + slot);
	}

	private static void doPurge(Path bin) throws IOException {
		if (!Files.isDirectory(bin)) {
			System.out.println("Recycle bin is already empty.");
			return;
		}
		int removed = 0;
		try (Stream<Path> entries = Files.list(bin)) {
			java.util.List<Path> children = entries.collect(java.util.stream.Collectors.toList());
			for (Path entry : children) {
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

	/** Replicates DesktopLauncher's per-OS data directory layout. */
	private static Path resolveDataDir() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");
		if (os.contains("win")) {
			return Paths.get(home, "AppData", "Roaming", "." + VENDOR, TITLE);
		} else if (os.contains("mac")) {
			return Paths.get(home, "Library", "Application Support", TITLE);
		} else {
			String xdg = System.getenv("XDG_DATA_HOME");
			Path base = (xdg == null) ? Paths.get(home, ".local", "share") : Paths.get(xdg);
			return base.resolve("." + VENDOR).resolve(
					TITLE.toLowerCase(Locale.ROOT).replace(" ", "-"));
		}
	}

	private static void printUsage() {
		System.out.println("Usage:");
		System.out.println("  list");
		System.out.println("  restore <snapshotId> <slot>");
		System.out.println("  purge");
	}
}
