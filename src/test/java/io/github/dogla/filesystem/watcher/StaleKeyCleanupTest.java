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

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

/**
 * When a watched directory disappears, its {@link java.nio.file.WatchKey} becomes invalid. The
 * cleanup used to remove the wrong map entry (it guessed the DIRECTORY as key, but single-file
 * watches are keyed by the FILE) - re-watching the same file after the directory was recreated
 * silently reused the dead registration and never delivered events again.
 *
 * @author Dominik Glaser
 */
@SuppressWarnings("nls")
public class StaleKeyCleanupTest {

	/**
	 * Watch file, kill its directory, recreate it, watch again: the new watch must deliver.
	 *
	 * @throws Exception on error
	 */
	@Test
	public void rewatching_after_directory_recreation_delivers_events_again() throws Exception {
		Path root = Files.createTempDirectory("fsw-stale");
		FileSystemWatcher watcher = new FileSystemWatcher("stale-test", true);
		try {
			Path dir = root.resolve("sub");
			Files.createDirectories(dir);
			Path file = dir.resolve("test.txt");
			Files.write(file, "v1".getBytes(StandardCharsets.UTF_8));

			List<FileSystemEvent> first = new CopyOnWriteArrayList<>();
			watcher.watchPath(file.toFile(), first::add);

			// nuke the watched directory -> the WatchKey turns invalid
			Files.delete(file);
			Files.delete(dir);
			Thread.sleep(1000); // let the watch thread process the invalidation

			// recreate and watch the same file again with a fresh listener
			Files.createDirectories(dir);
			Files.write(file, "v2".getBytes(StandardCharsets.UTF_8));
			List<FileSystemEvent> second = new CopyOnWriteArrayList<>();
			watcher.watchPath(file.toFile(), second::add);

			Thread.sleep(500);
			Files.write(file, "v3 modified".getBytes(StandardCharsets.UTF_8));

			long deadline = System.currentTimeMillis() + 5000;
			while (second.isEmpty() && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
			assertTrue("the re-registered watch must deliver events again: " + second, !second.isEmpty());
		} finally {
			watcher.close();
			deleteRecursively(root.toFile());
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
