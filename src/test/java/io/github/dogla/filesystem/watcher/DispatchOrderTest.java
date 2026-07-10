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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

/**
 * Events must reach the listeners in the order of their batches. The dispatch used to spawn a NEW
 * thread per batch - a slow listener in batch 1 let batch 2 overtake it, so consumers saw event
 * sequences that never happened on disk.
 *
 * @author Dominik Glaser
 */
@SuppressWarnings("nls")
public class DispatchOrderTest {

	private static WatchEvent<Path> event(final Path context) {
		return new WatchEvent<Path>() {
			@Override public Kind<Path> kind() { return StandardWatchEventKinds.ENTRY_MODIFY; }
			@Override public int count() { return 1; }
			@Override public Path context() { return context; }
		};
	}

	/**
	 * Two batches dispatched back to back: a listener that is slow for the FIRST event must not
	 * see the second batch first.
	 *
	 * @throws Exception on error
	 */
	@Test
	public void batches_are_delivered_in_order_even_with_a_slow_listener() throws Exception {
		Path dir = Files.createTempDirectory("fsw-order");
		FileSystemWatcher watcher = new FileSystemWatcher("order-test", true);
		try {
			Path a = dir.resolve("a.txt");
			Path b = dir.resolve("b.txt");
			Files.write(a, "a".getBytes(StandardCharsets.UTF_8));
			Files.write(b, "b".getBytes(StandardCharsets.UTF_8));

			List<String> order = new CopyOnWriteArrayList<>();
			FileSystemWatcher.WatchKeyData data = watcher.new WatchKeyData(dir, new FileSystemConfig());
			data.listeners.add(event -> {
				if (event.getFile().getName().equals("a.txt")) {
					try {
						Thread.sleep(300); // slow consumer on the first batch
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				order.add(event.getFile().getName());
			});

			watcher.handleEvents(dir, data, Arrays.asList(event(dir.relativize(a))));
			watcher.handleEvents(dir, data, Arrays.asList(event(dir.relativize(b))));

			Thread.sleep(1500);
			assertEquals(Arrays.asList("a.txt", "b.txt"), order);
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
