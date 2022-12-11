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

import java.io.File;

@SuppressWarnings({ "javadoc", "nls" })
public class FileSystemWatcherSnippets {
	
	public static void main(String[] args) {
		//watchFile();
	}
	
	public static void watchFile() {
		FileSystemWatcher fsWatcher = new FileSystemWatcher("my-filesystem-watcher");
		fsWatcher.watchPath(new File("C:\\myapp\\config.json"), (event) -> {
			switch (event.getType()) {
				case ADDED:
					System.out.println("File added");
					break;
				case MODIFIED:
					System.out.println("File modified");
					break;
				case REMOVED:
					System.out.println("File removed");
					break;
			}
		});
	}

	public static void watchFileWithAllowedEventTypes() {
		FileSystemWatcher fsWatcher = new FileSystemWatcher("my-filesystem-watcher");
		FileSystemConfig config = new FileSystemConfig().withAllowedEventTypes(FileSystemEventType.MODIFIED);
		fsWatcher.watchPath(new File("C:\\myapp\\config.json"), (event) -> {
			System.out.println("File modified");
		}, config);
	}

	public static void watchFolder() {
		FileSystemWatcher fsWatcher = new FileSystemWatcher("my-filesystem-watcher");
		fsWatcher.watchPath(new File("C:\\myapp\\"), (event) -> {
			switch (event.getType()) {
				case ADDED:
					System.out.println("File added: " + event.getFile());
					break;
				case MODIFIED:
					System.out.println("File modified: " + event.getFile());
					break;
				case REMOVED:
					System.out.println("File removed: " + event.getFile());
					break;
			}
		});
	}

	public static void watchFolderWithMaxDepth() {
		FileSystemWatcher fsWatcher = new FileSystemWatcher("my-filesystem-watcher");
		FileSystemConfig config = new FileSystemConfig().withMaxDepth(2);
		fsWatcher.watchPath(new File("C:\\myapp\\"), (event) -> {
			switch (event.getType()) {
				case ADDED:
					System.out.println("File added: " + event.getFile());
					break;
				case MODIFIED:
					System.out.println("File modified: " + event.getFile());
					break;
				case REMOVED:
					System.out.println("File removed: " + event.getFile());
					break;
			}
		}, config);
	}
	
	public static void watchFolderWithFilter() {
		FileSystemWatcher fsWatcher = new FileSystemWatcher("my-filesystem-watcher");
		FileSystemConfig config = new FileSystemConfig().withFilter((path) -> path.toFile().getName().endsWith(".json"));
		fsWatcher.watchPath(new File("C:\\myapp\\"), (event) -> {
			switch (event.getType()) {
				case ADDED:
					System.out.println("File added: " + event.getFile());
					break;
				case MODIFIED:
					System.out.println("File modified: " + event.getFile());
					break;
				case REMOVED:
					System.out.println("File removed: " + event.getFile());
					break;
			}
		}, config);
	}
	
	
}
