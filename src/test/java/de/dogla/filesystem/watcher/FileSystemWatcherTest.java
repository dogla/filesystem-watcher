/**
 * Copyright (C) 2020-2021 Dominik Glaser
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
package de.dogla.filesystem.watcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

/**
 * Test suite for the filesystem watcher.
 *
 * @author Dominik Glaser
 */
@SuppressWarnings("nls")
public class FileSystemWatcherTest {
	
	private FileSystemWatcher fsWatcher = new FileSystemWatcher("filesystem-watcher");
	
	/**
	 * This method would normally raise 3 events, but we reduce them to one event if they occur directly after each other in short time.
	 * @throws Exception
	 */
	@Test
	public void test_event_reduction() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File file = new File("C:\\resources\\watchPath\\sample.txt");
			file.delete();
			
			fsWatcher.watchPath(file, simpleListener(log));
			
			createTempFile(file.getParentFile(), "sample.txt");
			
			writeStringToFile(file, "mydata", "UTF-8");
			
			file.delete();
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("MODIFIED: C:\\resources\\watchPath\\sample.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}
	
	/**
	 * This method should raise 3 events because we added some additional delay between the different OS events.
	 * @throws Exception
	 */
	@Test
	public void test_event_reduction_with_delay_between_events() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File file = new File("C:\\resources\\watchPath\\sample.txt");
			file.delete();
			
			fsWatcher.watchPath(file, simpleListener(log));
			
			createTempFile(file.getParentFile(), "sample.txt");
			sleep(1000);
			
			writeStringToFile(file, "mydata", "UTF-8");
			sleep(1000);
			
			file.delete();
			sleep(1000);
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\sample.txt\n" +
					"MODIFIED: C:\\resources\\watchPath\\sample.txt\n" +
					"REMOVED: C:\\resources\\watchPath\\sample.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * This method should raise only the first event because we unregistered the event listener in the first call.
	 * @throws Exception
	 */
	@Test
	public void test_event_unwatch() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File file = new File("C:\\resources\\watchPath\\sample.txt");
			file.delete();
			
			fsWatcher.watchPath(file, new FileSystemListener() {
				@Override
				public void fileChanged(FileSystemEvent event) {
					fsWatcher.unwatchPath(file, this);
					log.add(event.getType() + ": " + event.getFile().getAbsolutePath());
				}
			});
			
			createTempFile(file.getParentFile(), "sample.txt");
			sleep(1000);
			
			writeStringToFile(file, "mydata", "UTF-8");
			sleep(1000);
			
			file.delete();
			sleep(1000);
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("ADDED: C:\\resources\\watchPath\\sample.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * This method tests file modification of a given file. All other events should be ignored.
	 * @throws Exception
	 */
	@Test
	public void test_watch_file() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File file = new File("C:\\resources\\watchPath\\sample.txt");
			file.delete();
			new File("C:\\resources\\watchPath\\sample2.txt").delete();
			new File("C:\\resources\\watchPath\\sample3.txt").delete();
			
			fsWatcher.watchPath(file, simpleListener(log));
			
			createTempFile(file.getParentFile(), "sample.txt");
			createTempFile(file.getParentFile(), "sample2.txt");
			createTempFile(file.getParentFile(), "sample3.txt");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("ADDED: C:\\resources\\watchPath\\sample.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * This method tests file modification in the registered folder.
	 * @throws Exception
	 */
	@Test
	public void test_watch_directory() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			new File("C:\\resources\\watchPath\\sample.txt").delete();
			new File("C:\\resources\\watchPath\\sample2.txt").delete();
			new File("C:\\resources\\watchPath\\sample3.txt").delete();
			
			fsWatcher.watchPath(dir, simpleListener(log));
			
			createTempFile(dir, "sample.txt");
			createTempFile(dir, "sample2.txt");
			createTempFile(dir, "sample3.txt");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\sample.txt\n" +
					"ADDED: C:\\resources\\watchPath\\sample2.txt\n" +
					"ADDED: C:\\resources\\watchPath\\sample3.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * This method tests file creations in subdirectories not directly registered at start: with no explicit max-depth (= 1).
	 * @throws Exception
	 */
	@Test
	public void test_watch_subdirectory() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath");
			deleteDirectory("C:\\resources\\watchPath");
			
			fsWatcher.watchPath(dir, simpleListener(log));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
		} finally {
			sleep(2000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\n" +
					"ADDED: C:\\resources\\watchPath\\1\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\n" +
					"MODIFIED: C:\\resources\\watchPath", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * This method tests file creations in subdirectories not directly registered at start.
	 * @throws Exception
	 */
	@Test
	public void test_watch_subdirectory_recursivly() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			unwatchPaths();
			File dir = new File("C:\\resources\\watchPath");
			deleteDirectory("C:\\resources\\watchPath");
			
			watchPathRecursively(dir, simpleListener(log));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
			sleep(1000);
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\n" +
					"ADDED: C:\\resources\\watchPath\\1\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample.txt\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample2.txt\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\\2\n" +
					"MODIFIED: C:\\resources\\watchPath\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample3.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}


	/**
	 * This method tests file creations in subdirectories not directly registered at start: with max-depth: 3.
	 * @throws Exception
	 */
	@Test
	public void test_watch_subdirectory_max_depth_of_3() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(3));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample.txt\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample2.txt\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\\2\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample3.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * Test max-depth: 3
	 * @throws Exception
	 */
	@Test
	public void test_maxdepth_3() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(3));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample.txt\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample2.txt\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\\2\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample3.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * Test max-depth: 2
	 * @throws Exception
	 */
	@Test
	public void test_maxdepth_2() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(2));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\\2\n" +
					"MODIFIED: C:\\resources\\watchPath\\1", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * Test max-depth: 1
	 * @throws Exception
	 */
	@Test
	public void test_maxdepth_1() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(1));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\n" +
					"MODIFIED: C:\\resources\\watchPath\\1", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * This method calls watchPath without a config parameter. This should be the same like a config with max-depth = 1.
	 * @throws Exception
	 */
	@Test
	public void test_watchPath_simple() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			fsWatcher.watchPath(dir, simpleListener(log));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\n" +
					"MODIFIED: C:\\resources\\watchPath\\1", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	* Test option - include-files: false.
	 * @throws Exception
	*/
	@Test
	public void test_watchPath_option_not_include_files() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(3).withFilter(notIncludeFiles()));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.json", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.json", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\\2\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\\2\n" +
					"MODIFIED: C:\\resources\\watchPath\\1\\2", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	* Test option - include-directories: false.
	 * @throws Exception
	 */
	@Test
	public void test_watchPath_option_not_include_directories() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(3).withFilter(notIncludeDirectories()));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.json", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.json", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample.txt\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample.xml\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample2.json\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample2.txt\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample3.txt\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample3.xml\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample3.json", log.stream().collect(Collectors.joining("\n")));
		}
	}


	/**
	 * Test option - include-regex: *.\.xml.
	 * @throws Exception
	 */
	@Test
	public void test_watchPath_option_include_XML() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(3).withFilter(includeRegEx(new String[] { ".*\\.xml" })));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.json", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.json", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample.xml\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample3.xml", log.stream().collect(Collectors.joining("\n")));
		}
	}


	/**
	 * Test option - include-glob: ** /*.xml.
	 * @throws Exception
	 */
	@Test
	public void test_watchPath_option_include_glob_xml() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\1");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			dir.mkdirs();
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(3).withFilter(includeGlob(new String[] { "**/*.xml" })));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.json", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.json", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.json", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\\sample.xml\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample.xml\n" +
					"ADDED: C:\\resources\\watchPath\\1\\2\\sample3.xml", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * Test max-depth 3 and glob filter that matches XML files only in the direct folder
	 * @throws Exception
	 */
	@Test
	public void test_watchPath_option_include_glob_direct_folder_only() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\1");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			dir.mkdirs();
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(3).withFilter(includeGlob(new String[] { "C:\\\\resources\\\\watchPath\\\\1\\\\*.xml" })));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.json", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.json", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.json", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\\sample.xml", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * Test max-depth 1 and glob filter that matches XML files in any folder
	 * @throws Exception
	 */
	@Test
	public void test_watchPath_option_include_glob_directories() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File dir = new File("C:\\resources\\watchPath\\1");
			deleteDirectory("C:\\resources\\watchPath\\1");
			
			dir.mkdirs();
			fsWatcher.watchPath(dir, simpleListener(log), new FileSystemConfig().withMaxDepth(1).withFilter(includeGlob(new String[] { "**.xml" })));
			
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\sample.json", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample2.json", "1", "UTF-8");
			sleep(1000);
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.txt", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.xml", "1", "UTF-8");
			writeStringToFile("C:\\resources\\watchPath\\1\\2\\sample3.json", "1", "UTF-8");
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\1\\sample.xml", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * Tests for the events: added
	 * @throws Exception
	 */
	@Test
	public void test_event_types_added() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File file = new File("C:\\resources\\watchPath\\sample.txt");
			file.delete();
			
			fsWatcher.watchPath(file, simpleListener(log), new FileSystemConfig().withAllowedEventTypes(FileSystemEventType.ADDED));
			
			createTempFile(file.getParentFile(), "sample.txt");
			sleep(1000);
			
			writeStringToFile(file, "mydata", "UTF-8");
			sleep(1000);
			
			file.delete();
			sleep(1000);
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\sample.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}

	/**
	 * Tests for the events: added and removed
	 * @throws Exception
	 */
	@Test
	public void test_event_types_added_removed() throws Exception {
		List<String> log = new ArrayList<>();
		try {
			File file = new File("C:\\resources\\watchPath\\sample.txt");
			file.delete();
			
			fsWatcher.watchPath(file, simpleListener(log), new FileSystemConfig().withAllowedEventTypes(FileSystemEventType.ADDED, FileSystemEventType.REMOVED));
			
			createTempFile(file.getParentFile(), "sample.txt");
			sleep(1000);
			
			writeStringToFile(file, "mydata", "UTF-8");
			sleep(1000);
			
			file.delete();
			sleep(1000);
		} finally {
			sleep(1000);
			unwatchPaths();
			assertEquals("" +
					"ADDED: C:\\resources\\watchPath\\sample.txt\n" +
					"REMOVED: C:\\resources\\watchPath\\sample.txt", log.stream().collect(Collectors.joining("\n")));
		}
	}

	private void watchPathRecursively(File file, FileSystemListener listener) {
		FileSystemConfig config = new FileSystemConfig();
		config.withMaxDepth(Integer.MAX_VALUE);
		fsWatcher.watchPath(file, listener, config);
	}
	
	private void unwatchPaths() {
		fsWatcher.unwatchPaths();
	}
	
	private static void createTempFile(File directory, String fileName) throws IOException {
		new File(directory, fileName).createNewFile();
	}
	
	private static void writeStringToFile(String file, String content, String encoding) throws IOException {
		writeStringToFile(new File(file), content, encoding);
	}
	
	private static void writeStringToFile(File file, String content, String encoding) throws IOException {
		FileUtils.writeStringToFile(file, content, encoding);
	}
	
	private static void sleep(long millis) throws InterruptedException {
		Thread.sleep(millis);
	}
	
	private static FileSystemListener simpleListener(List<String> log) {
		return event -> {
			assertNotNull(event.getFile());
			assertNotNull(event.getType());
			log.add(event.getType() + ": " + event.getFile().getAbsolutePath());
		};
	}

	private static void deleteDirectory(String directory) {
		try {
			FileUtils.deleteDirectory(new File(directory));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	// TODO add API for standard filters
	private static Predicate<Path> notIncludeFiles() {
		return new Predicate<Path>() {
			@Override
			public boolean test(Path t) {
				return !t.toFile().isFile();
			}
		};
	}

	private static Predicate<Path> notIncludeDirectories() {
		return new Predicate<Path>() {
			@Override
			public boolean test(Path t) {
				return !t.toFile().isDirectory();
			}
		};
	}
	
	private static Predicate<Path> includeRegEx(String[] patterns) {
		Predicate<Path> sub = p -> false;
		for (String pattern : patterns) {
			PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("regex:"+pattern);
			sub = sub.or(p -> pathMatcher.matches(p));
		}
		return sub;
	}
	
	private static Predicate<Path> includeGlob(String[] patterns) {
		Predicate<Path> sub = p -> false;
		for (String pattern : patterns) {
			PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:"+pattern);
			sub = sub.or(p -> pathMatcher.matches(p));
		}
		return sub;
	}
	
}
