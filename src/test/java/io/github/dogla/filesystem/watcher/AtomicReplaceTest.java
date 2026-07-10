/**
 * Copyright (C) 2020-2022 Dominik Glaser
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dogla.filesystem.watcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

/**
 * The batch coalescing must not swallow atomic file replacements: modern writers (e.g. Claude
 * Code) replace a file via "write temp + DELETE target + RENAME temp to target". Both DELETE and
 * CREATE of the target land in the same poll batch and used to cancel each other - no event was
 * delivered. Windows emits NO accompanying MODIFY for the rename (unlike
 * {@link java.nio.file.Files#move}, which masks the bug), so the coalescing has to synthesize a
 * MODIFIED event itself. These tests feed the measured event sequences directly into
 * {@code handleEvents} - deterministic, independent of the writer.
 *
 * @author Dominik Glaser
 */
@SuppressWarnings("nls")
public class AtomicReplaceTest {

	private static WatchEvent<Path> event(final WatchEvent.Kind<Path> kind, final Path context) {
		return new WatchEvent<Path>() {
			@Override public Kind<Path> kind() { return kind; }
			@Override public int count() { return 1; }
			@Override public Path context() { return context; }
		};
	}

	private static List<FileSystemEvent> dispatch(Path dir, List<WatchEvent<?>> events) throws Exception {
		FileSystemWatcher watcher = new FileSystemWatcher("atomic-replace-test", true);
		try {
			List<FileSystemEvent> received = new CopyOnWriteArrayList<>();
			FileSystemWatcher.WatchKeyData data = watcher.new WatchKeyData(dir, new FileSystemConfig());
			data.listeners.add(received::add);
			watcher.handleEvents(dir, data, events);
			// the listener is notified on a background thread
			long deadline = System.currentTimeMillis() + 2000;
			while (System.currentTimeMillis() < deadline) {
				Thread.sleep(20);
			}
			return new ArrayList<>(received);
		} finally {
			watcher.close();
		}
	}

	/**
	 * The measured Claude Code sequence for an EXISTING file: DELETE target + CREATE target in one
	 * batch (the temp file has a different name and is filtered by consumers). Must be delivered
	 * as exactly one MODIFIED.
	 *
	 * @throws Exception on error
	 */
	@Test
	public void atomic_replace_of_existing_file_is_delivered_as_modified() throws Exception {
		Path dir = Files.createTempDirectory("fsw-atomic");
		try {
			Path target = dir.resolve("test.js");
			Files.write(target, "// replaced".getBytes(StandardCharsets.UTF_8));

			List<FileSystemEvent> received = dispatch(dir, Arrays.asList(
					event(StandardWatchEventKinds.ENTRY_DELETE, dir.relativize(target)),
					event(StandardWatchEventKinds.ENTRY_CREATE, dir.relativize(target))));

			assertEquals("expected exactly one event: " + received, 1, received.size());
			assertEquals(FileSystemEventType.MODIFIED, received.get(0).getType());
			assertEquals(target.toFile(), received.get(0).getFile());
		} finally {
			deleteRecursively(dir.toFile());
		}
	}

	/**
	 * A transient temp file (created and deleted within one batch, gone afterwards) must still
	 * net to nothing - no synthesized MODIFIED for a file that no longer exists.
	 *
	 * @throws Exception on error
	 */
	@Test
	public void transient_temp_file_nets_to_nothing() throws Exception {
		Path dir = Files.createTempDirectory("fsw-transient");
		try {
			Path temp = dir.resolve("gone.tmp");

			List<FileSystemEvent> received = dispatch(dir, Arrays.asList(
					event(StandardWatchEventKinds.ENTRY_CREATE, dir.relativize(temp)),
					event(StandardWatchEventKinds.ENTRY_DELETE, dir.relativize(temp))));

			assertTrue("expected no events for a transient file: " + received, received.isEmpty());
		} finally {
			deleteRecursively(dir.toFile());
		}
	}

	/**
	 * XNIO-344 compatibility: if a MODIFIED accompanies the add+remove pair, that event is kept
	 * (and nothing is synthesized on top).
	 *
	 * @throws Exception on error
	 */
	@Test
	public void replace_with_accompanying_modify_keeps_the_modify_only() throws Exception {
		Path dir = Files.createTempDirectory("fsw-modify");
		try {
			Path target = dir.resolve("test.js");
			Files.write(target, "// replaced".getBytes(StandardCharsets.UTF_8));

			List<FileSystemEvent> received = dispatch(dir, Arrays.asList(
					event(StandardWatchEventKinds.ENTRY_DELETE, dir.relativize(target)),
					event(StandardWatchEventKinds.ENTRY_CREATE, dir.relativize(target)),
					event(StandardWatchEventKinds.ENTRY_MODIFY, dir.relativize(target))));

			assertEquals("expected exactly one event: " + received, 1, received.size());
			assertEquals(FileSystemEventType.MODIFIED, received.get(0).getType());
		} finally {
			deleteRecursively(dir.toFile());
		}
	}

	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		file.delete();
	}
}
