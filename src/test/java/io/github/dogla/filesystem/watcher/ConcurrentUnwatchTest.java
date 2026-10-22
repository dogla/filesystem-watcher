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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Several watched files in ONE directory share one {@link java.nio.file.WatchKey}. A listener that
 * re-registers its file (unwatchPath + watchPath, e.g. a reload on change) mutates the key's
 * registration list on the dispatcher thread while the watch thread still iterates it. The
 * resulting ConcurrentModificationException killed the watch thread silently - no event was
 * delivered ever again.
 *
 * @author Dominik Glaser
 */
@SuppressWarnings("nls")
public class ConcurrentUnwatchTest {

	private static final int FILES = 50;
	private static final int ROUNDS = 5;

	/**
	 * Re-registering listeners must not kill the watch thread.
	 *
	 * @throws Exception on error
	 */
	@Test
	public void reregistering_listeners_keep_the_watch_thread_alive() throws Exception {
		Path dir = Files.createTempDirectory("fsw-unwatch");
		String name = "unwatch-test-" + System.nanoTime();
		FileSystemWatcher watcher = new FileSystemWatcher(name, true);
		try {
			AtomicInteger deliveries = new AtomicInteger();
			List<File> files = new ArrayList<>();
			for (int i = 0; i < FILES; i++) {
				Path file = dir.resolve("config-" + i + ".json");
				Files.write(file, "v0".getBytes(StandardCharsets.UTF_8));
				files.add(file.toFile());
			}
			for (File file : files) {
				watcher.watchPath(file, new FileSystemListener() {
					@Override
					public void fileChanged(FileSystemEvent event) {
						deliveries.incrementAndGet();
						watcher.unwatchPath(file, this);
						watcher.watchPath(file, this);
					}
				});
			}

			for (int round = 1; round <= ROUNDS; round++) {
				for (File file : files) {
					Files.write(file.toPath(), ("v" + round).getBytes(StandardCharsets.UTF_8));
				}
				Thread.sleep(300);
			}

			int before = deliveries.get();
			Files.write(files.get(0).toPath(), "final".getBytes(StandardCharsets.UTF_8));
			long deadline = System.currentTimeMillis() + 5000;
			while (deliveries.get() == before && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
			assertTrue("watch thread 'file-system-watcher[" + name + "]' must still be alive",
					isAlive("file-system-watcher[" + name + "]"));
			assertTrue("a change after the re-registrations must still be delivered", deliveries.get() > before);
		} finally {
			watcher.close();
			for (File file : dir.toFile().listFiles()) {
				file.delete();
			}
			dir.toFile().delete();
		}
	}

	private static boolean isAlive(String threadName) {
		return Thread.getAllStackTraces().keySet().stream()
				.anyMatch(t -> t.getName().equals(threadName) && t.isAlive());
	}
}
