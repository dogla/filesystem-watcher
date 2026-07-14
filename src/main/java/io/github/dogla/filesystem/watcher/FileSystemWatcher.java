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

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A file system watcher that internally starts another thread for the event handling.
 * Inspired by the XNIO WatchServiceFileSystemWatcher.
 *
 * @author Dominik Glaser
 * @since 1.0
 */
public class FileSystemWatcher implements Runnable {

	private static Logger logger = LoggerFactory.getLogger(FileSystemWatcher.class);
	
    private WatchService watchService;
    private final Map<File, WatchKeyData> dataByFile = Collections.synchronizedMap(new HashMap<File, WatchKeyData>());
    private final Map<WatchKey, List<WatchKeyData>> datesByKey = Collections.synchronizedMap(new IdentityHashMap<WatchKey, List<WatchKeyData>>());

    private volatile boolean stopped = false;
    private final Thread watchThread;
    /** Single dispatcher: listeners must receive the batches in order (a thread per batch let a
     * later batch overtake a slow listener). */
    private final java.util.concurrent.ExecutorService dispatcher;

    /**
     * Constructor.
     *
     * @param name the name of the file system watcher (used as part of the watch service thread name)
     */
    public FileSystemWatcher(final String name) {
    	this(name, /*daemon*/ false);
    }

    /**
     * Constructor.
     *
     * @param name the name of the file system watcher (used as part of the watch service thread name)
     * @param daemon flag indicating if the underlying file system watcher thread should be a daemon thread
     */
    public FileSystemWatcher(final String name, boolean daemon) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        	Thread t = new Thread(r, "file-system-watcher-dispatch[" + name + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        	t.setDaemon(daemon);
        	return t;
        });
        watchThread = new Thread(this, "file-system-watcher[" + name + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        watchThread.setDaemon(daemon);
        watchThread.start();
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
            	//System.err.println("take() run");
                final WatchKey key = watchService.take();
                if (key != null) {
                	// Prevent receiving two separate ENTRY_MODIFY events: file modified
                    // and timestamp updated. Instead, receive one ENTRY_MODIFY event
                    // with two counts.
                    Thread.sleep(100);
                    
                	Path watchablePath = (Path) key.watchable();
                    try {
                    	//System.err.println("take() returned");
                    	List<WatchEvent<?>> events = key.pollEvents();
                        List<WatchKeyData> pathDates = datesByKey.get(key);
                        if (pathDates != null) {
                        	for (WatchKeyData pathData : pathDates) {
								handleEvents(watchablePath, pathData, events);
							}
                        }
                    } finally {
                        // an invalid key means its directory is gone: drop the key everywhere so a
                        // later watchPath on the same path registers freshly (the old code removed
                        // the wrong dataByFile entry - single-file watches are keyed by the FILE,
                        // not the directory - and left dead registrations behind)
                        if (!key.reset()) {
                            List<WatchKeyData> invalidated = datesByKey.remove(key);
                            if (invalidated != null) {
                                for (WatchKeyData data : invalidated) {
                                    data.keys.remove(key);
                                    if (data.keys.isEmpty()) {
                                        synchronized (dataByFile) {
                                            dataByFile.values().removeIf(value -> value == data);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                //ignore
            	//System.err.println("InterruptedException");
            } catch (ClosedWatchServiceException cwse) {
                // the watcher service is closed, so no more waiting on events
            	//System.err.println("ClosedWatchServiceException");
                break;
            }
        }
    }

	/**
	 * Handles the occured watch events.
	 * 
	 * @param watchablePath the watchable path
	 * @param pathData the path data
	 * @param events the events
	 */
	protected void handleEvents(Path watchablePath, WatchKeyData pathData, List<WatchEvent<?>> events) {
		//System.err.println("FileSystemWatcher.handleEvents()");
		final List<FileSystemEvent> results = new ArrayList<>();
		final Set<File> addedFiles = new HashSet<>();
		final Set<File> deletedFiles = new HashSet<>();
		FileSystemConfig config = pathData.config;
		for (WatchEvent<?> event : events) {
			//System.err.println("\t" + event.context() + " | " + pathData.path);
			if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
				// the OS dropped events - surface it as a MODIFIED of the watched path so
				// consumers get a chance to rescan (silently swallowing it means missed changes)
				logger.warn("Watch event overflow for {} - some events were lost", pathData.path); //$NON-NLS-1$
				if (config.isEventAllowed(FileSystemEventType.MODIFIED)) {
					FileSystemEvent overflowEvent = new FileSystemEvent(pathData.path.toFile(), FileSystemEventType.MODIFIED);
					results.add(overflowEvent);
				}
				continue;
			}
			Path eventPath = (Path) event.context();
			Path targetPath = watchablePath.resolve(eventPath);
			File targetFile = targetPath.toFile();
			//System.err.println(" - " + targetFile.getAbsolutePath());
			// check if the changed path IS the watched path or lies below it (needed for single
			// file watching) - component-wise, so a sibling that merely starts with the same
			// name (test.js vs. test.js.tmp.12345, dir vs. dir2) does not match
			if (!targetPath.startsWith(pathData.path)) {
				continue;
			}
			// check config
			int currentDepth = targetPath.getNameCount() - pathData.path.getNameCount();
			if (currentDepth > config.getMaxDepth()) {
				continue;
			}
			FileSystemEventType type = null;
		    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
		        type = FileSystemEventType.ADDED;
		        if (!config.isEventAllowed(type)) {
		        	continue;
		        }
		        addedFiles.add(targetFile);
		    } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
		        type = FileSystemEventType.MODIFIED;
		        if (!config.isEventAllowed(type)) {
		        	continue;
		        }
		    } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
		        type = FileSystemEventType.REMOVED;
		        if (!config.isEventAllowed(type)) {
		        	continue;
		        }
		        deletedFiles.add(targetFile);
		    }
		    if (targetFile.isDirectory() && FileSystemEventType.ADDED == type) {
		    	int depth = config.getMaxDepth() - currentDepth;
		    	if (depth > 0) {
			    	try {
			    		// watch all valid sub directories
			    		Files.walk(targetFile.toPath(), depth).forEach(p -> {
			    			File file = p.toFile();
			    			if (file.isDirectory()) {
			    				if (p.getNameCount() - pathData.path.getNameCount() <= config.getMaxDepth()) {
			    					try {
			    						addWatchedDirectory(pathData, file);
			    					} catch (IOException e) {
			    						logger.warn("Could not watch directory: {}", file, e); //$NON-NLS-1$
			    					}
			    				}
			    			}
			    			results.add(new FileSystemEvent(file, FileSystemEventType.ADDED));
			    		});
			    	} catch (IOException e) {
			    		logger.warn("Could not traverse directory: {}", targetFile, e); //$NON-NLS-1$
			    	}
		    	} else {
		    		// directory content should not be watched because it reached the max-depth setting
		    		results.add(new FileSystemEvent(targetFile, FileSystemEventType.ADDED));
		    	}
		    } else {
		    	results.add(new FileSystemEvent(targetFile, type));
		    }
		}

		// coalesce duplicate events within one batch
		// e.g. if the file is modified right after creation we only report the ADDED event.
		// A file that is BOTH added and removed in the same batch is either a transient temp file
		// (create -> delete: nets to nothing) or an ATOMIC REPLACEMENT (delete -> rename-in, the
		// write pattern of modern editors and e.g. Claude Code). A replacement leaves the file
		// existing and MUST surface as a change - Windows delivers no accompanying MODIFY for the
		// rename, so we synthesize a single MODIFIED event in that case (the old behaviour dropped
		// ADDED and REMOVED against each other and the replacement was invisible to listeners).
		{
			Set<File> replacedFiles = new HashSet<>(addedFiles);
			replacedFiles.retainAll(deletedFiles);
			Set<File> modifiedFiles = new HashSet<>();
			for (FileSystemEvent event : results) {
				if (event.getType() == FileSystemEventType.MODIFIED) {
					modifiedFiles.add(event.getFile());
				}
			}
			Iterator<FileSystemEvent> it = results.iterator();
			while (it.hasNext()) {
				FileSystemEvent event = it.next();
				File file = event.getFile();
				if (replacedFiles.contains(file)) {
					// keep only MODIFIED for added+removed files (XNIO-344); ADDED/REMOVED cancel out
					if (event.getType() != FileSystemEventType.MODIFIED) {
						it.remove();
					}
				} else if (event.getType() == FileSystemEventType.MODIFIED
						&& (addedFiles.contains(file) || deletedFiles.contains(file))) {
					it.remove();
				}
			}
			for (File file : replacedFiles) {
				if (!modifiedFiles.contains(file) && file.exists()) {
					// delete+create without a modify in the same batch = atomic replacement
					results.add(new FileSystemEvent(file, FileSystemEventType.MODIFIED));
				}
			}
		}
		
		// consider custom filter
		Predicate<Path> filter = config.getFilter();
		if (filter != null) {
			Iterator<FileSystemEvent> it = results.iterator();
			while (it.hasNext()) {
			    FileSystemEvent event = it.next();
			    File file = event.getFile();
			    if (!filter.test(file.toPath())) {
			    	it.remove();
			    }
			}
		}

		if (!results.isEmpty()) {
			try {
				// handle the events off the watch thread but IN ORDER (single dispatcher)
				dispatcher.execute(() -> {
					for (FileSystemEvent event : results) {
						FileSystemListener[] listeners = pathData.listeners.toArray(new FileSystemListener[pathData.listeners.size()]);
						for (FileSystemListener listener : listeners) {
							try {
								listener.fileChanged(event);	
							} catch (Exception e) {
								logger.error(e.getMessage(), e);
							}							
						}
				    }
				});
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		}
		//System.err.println("FileSystemWatcher.handleEvents() - end");
	}

    /**
     * Registers the given listener for the given file.
     * 
     * @param file the file to watch
     * @param listener the lisener
     */
	public synchronized void watchPath(File file, FileSystemListener listener) {
		watchPath(file, listener, new FileSystemConfig());
	}
	
    /**
     * Registers the given listener for the given file.
     * 
     * @param file the file to watch
     * @param listener the lisener
     * @param config the config
     */
    public synchronized void watchPath(File file, FileSystemListener listener, FileSystemConfig config) {
    	//System.err.println("FileSystemWatcher.watchPath()");
    	if (config == null) {
    		throw new IllegalArgumentException("The variable config may not be null"); //$NON-NLS-1$
    	}
        try {
            WatchKeyData data = dataByFile.get(file);
            if (data == null) {
                data = new WatchKeyData(Paths.get(file.toURI()), config);
                if (file.isDirectory()) {
	        		try {
	        			final WatchKeyData d = data;
	        			Files.walk(file.toPath(), config.getMaxDepth()-1).forEach(p -> {
	        				File f = p.toFile();
	        				if (f.isDirectory()) {
	        					try {
	        						addWatchedDirectory(d, f);
	    		        		} catch (IOException e) {
	    		        			logger.warn("Could not watch directory: {}", f, e); //$NON-NLS-1$
	    		        		}
	        				}
	        			});
	        		} catch (IOException e) {
	        			logger.warn("Could not traverse directory: {}", file, e); //$NON-NLS-1$
	        		}
                } else {
                	// be sure the parent exists
                	if (!file.getParentFile().exists()) {
                		file.getParentFile().mkdirs();
                	}
                	addWatchedDirectory(data, file.getParentFile());
                }
                dataByFile.put(file, data);
            }
            data.listeners.add(listener);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        //System.err.println("FileSystemWatcher.watchPath() - end");
    }

    private void addWatchedDirectory(WatchKeyData data, File dir) throws IOException {
        Path path = Paths.get(dir.toURI());
        WatchKey key = path.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        datesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(data);
        data.keys.add(key);
    }

    /**
     * Unwatches the given file.
     * 
     * @param file the file to unwatch
     * @param listener the listener to remove
     */
    public synchronized void unwatchPath(File file, final FileSystemListener listener) {
        WatchKeyData data = dataByFile.get(file);
        if (data != null) {
            data.listeners.remove(listener);
            if (data.listeners.isEmpty()) {
                dataByFile.remove(file);
                for (WatchKey key : data.keys) {
                    List<WatchKeyData> list = datesByKey.get(key);
                    if (list != null) {
						list.remove(data);
						if (list.isEmpty()) {
		                	key.reset();
		                    key.cancel();
							datesByKey.remove(key);
						}
					}
                }
            }
        }
    }

	/**
	 * Unwatches all registered paths.
	 */
	public synchronized void unwatchPaths() {
		//System.err.println("FileSystemWatcher.unwatchPaths()");
		dataByFile.clear();
		for (List<WatchKeyData> values : datesByKey.values()) {
			for (WatchKeyData watchKeyData : values) {
				for (WatchKey key : watchKeyData.keys) {
					key.cancel();
				}
			}
		}
		datesByKey.clear();
		//System.err.println("FileSystemWatcher.unwatchPaths() - end");
	}

    /**
     * Closes the underlying watch service.
     */
    public void close() {
        this.stopped = true;
        dispatcher.shutdown();
        watchThread.interrupt();
        try {
            if (watchService != null) {
            	watchService.close();
            }
        } catch (final IOException ioe) {
            // ignore
        }
    }

    class WatchKeyData {
        private final Path path;
        private final FileSystemConfig config;
        // thread-safe: mutated by watch/unwatch while the dispatcher thread snapshots it
        final List<FileSystemListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<WatchKey> keys = new ArrayList<>();
		public WatchKeyData(Path path, FileSystemConfig config) {
			this.path = path;
			this.config = config;
		}
    }

}
