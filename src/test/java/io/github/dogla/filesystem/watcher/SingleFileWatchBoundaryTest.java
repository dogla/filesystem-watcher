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
 * A watch on a single file must only fire for exactly that file - not for siblings whose name
 * merely starts with the watched name (e.g. the temp files of atomic writers:
 * {@code test.js.tmp.12345}), and a watch on a directory must not fire for a sibling directory
 * whose name starts the same (e.g. {@code dir} vs {@code dir2}).
 *
 * @author Dominik Glaser
 */
@SuppressWarnings("nls")
public class SingleFileWatchBoundaryTest {

	private static WatchEvent<Path> event(final WatchEvent.Kind<Path> kind, final Path context) {
		return new WatchEvent<Path>() {
			@Override public Kind<Path> kind() { return kind; }
			@Override public int count() { return 1; }
			@Override public Path context() { return context; }
		};
	}

	private static List<FileSystemEvent> dispatch(Path watchedPath, Path watchableDir, List<WatchEvent<?>> events)
			throws Exception {
		FileSystemWatcher watcher = new FileSystemWatcher("boundary-test", true);
		try {
			List<FileSystemEvent> received = new CopyOnWriteArrayList<>();
			FileSystemWatcher.WatchKeyData data = watcher.new WatchKeyData(watchedPath, new FileSystemConfig());
			data.listeners.add(received::add);
			watcher.handleEvents(watchableDir, data, events);
			Thread.sleep(1000); // listener is notified asynchronously
			return new ArrayList<>(received);
		} finally {
			watcher.close();
		}
	}

	/**
	 * Watching {@code test.js} must not deliver events of the sibling {@code test.js.tmp.12345}.
	 *
	 * @throws Exception on error
	 */
	@Test
	public void single_file_watch_ignores_prefix_siblings() throws Exception {
		Path dir = Files.createTempDirectory("fsw-boundary");
		try {
			Path target = dir.resolve("test.js");
			Files.write(target, "// content".getBytes(StandardCharsets.UTF_8));
			Path temp = dir.resolve("test.js.tmp.12345");
			Files.write(temp, "// temp".getBytes(StandardCharsets.UTF_8));

			List<FileSystemEvent> received = dispatch(target, dir, Arrays.asList(
					event(StandardWatchEventKinds.ENTRY_MODIFY, dir.relativize(temp))));

			assertTrue("temp file events must not reach a single-file watch: " + received, received.isEmpty());
		} finally {
			deleteRecursively(dir.toFile());
		}
	}

	/**
	 * Watching {@code test.js} still delivers events of exactly that file.
	 *
	 * @throws Exception on error
	 */
	@Test
	public void single_file_watch_still_delivers_the_watched_file() throws Exception {
		Path dir = Files.createTempDirectory("fsw-boundary");
		try {
			Path target = dir.resolve("test.js");
			Files.write(target, "// content".getBytes(StandardCharsets.UTF_8));

			List<FileSystemEvent> received = dispatch(target, dir, Arrays.asList(
					event(StandardWatchEventKinds.ENTRY_MODIFY, dir.relativize(target))));

			assertEquals("expected exactly one event: " + received, 1, received.size());
			assertEquals(target.toFile(), received.get(0).getFile());
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
