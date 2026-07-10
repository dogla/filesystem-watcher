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

import java.io.File;
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
 * An OVERFLOW means the OS dropped events - listeners were never told and silently missed
 * changes. An overflow is now surfaced as a MODIFIED event for the watched path itself, so
 * consumers get a chance to rescan.
 *
 * @author Dominik Glaser
 */
@SuppressWarnings("nls")
public class OverflowTest {

	/**
	 * OVERFLOW surfaces as one MODIFIED for the watched path (instead of being swallowed).
	 *
	 * @throws Exception on error
	 */
	@Test
	public void overflow_is_surfaced_as_modified_for_the_watched_path() throws Exception {
		Path dir = Files.createTempDirectory("fsw-overflow");
		FileSystemWatcher watcher = new FileSystemWatcher("overflow-test", true);
		try {
			List<FileSystemEvent> received = new CopyOnWriteArrayList<>();
			FileSystemWatcher.WatchKeyData data = watcher.new WatchKeyData(dir, new FileSystemConfig());
			data.listeners.add(received::add);

			WatchEvent<Object> overflow = new WatchEvent<Object>() {
				@Override public Kind<Object> kind() { return StandardWatchEventKinds.OVERFLOW; }
				@Override public int count() { return 1; }
				@Override public Object context() { return null; }
			};
			watcher.handleEvents(dir, data, Arrays.asList(overflow));

			Thread.sleep(1000);
			List<FileSystemEvent> events = new ArrayList<>(received);
			assertEquals("expected exactly one event: " + events, 1, events.size());
			assertEquals(FileSystemEventType.MODIFIED, events.get(0).getType());
			assertEquals(dir.toFile(), events.get(0).getFile());
		} finally {
			watcher.close();
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
