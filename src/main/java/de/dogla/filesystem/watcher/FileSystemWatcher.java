/**
 * Copyright (C) 2020 Dominik Glaser
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
    private final Map<WatchKey, WatchKeyData> dataByKey = Collections.synchronizedMap(new IdentityHashMap<WatchKey, WatchKeyData>());

    private volatile boolean stopped = false;
    private final Thread watchThread;

    /**
     * Constructor.
     *
     * @param name the name of the file system watcher (used as part of the watch service thread name)
     */
    public FileSystemWatcher(final String name) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        watchThread = new Thread(this, "file-system-watcher[" + name + "]"); //$NON-NLS-1$ //$NON-NLS-2$
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
                        WatchKeyData pathData = dataByKey.get(key);
                        if (pathData != null) {
                            handleEvents(watchablePath, pathData, key.pollEvents());
                        }
                    } finally {
                        //if the key is no longer valid remove it from the files list
                        if (!key.reset()) {
                            dataByFile.remove(watchablePath.toFile());
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
		    Path eventPath = (Path) event.context();
			Path targetPath = watchablePath.resolve(eventPath);
			File targetFile = targetPath.toFile();
			//System.err.println(" - " + targetFile.getAbsolutePath());
			// check if changed file starts with the same path (needed for single file watching)
			if (!targetFile.getAbsolutePath().contains(pathData.path.toFile().getAbsolutePath())) {
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
		    } else if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
		    	logger.warn("Overflow detected: {}", targetFile); //$NON-NLS-1$
		    	continue;
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

		// filter duplicate events
		// e.g. if the file is modified after creation we only want to show the create event
		{
			Iterator<FileSystemEvent> it = results.iterator();
			while (it.hasNext()) {
				FileSystemEvent event = it.next();
				if (event.getType() == FileSystemEventType.MODIFIED) {
					if (addedFiles.contains(event.getFile()) &&
							deletedFiles.contains(event.getFile())) {
						// XNIO-344
						// All file change events (ADDED, REMOVED and MODIFIED) occurred here.
						// This happens when an updated file is moved from the different
						// filesystems or the directory having different project quota on Linux.
						// ADDED and REMOVED events will be removed in the latter conditional branching.
						// So, this MODIFIED event needs to be kept for the file change notification.
						continue;
					}
					if (addedFiles.contains(event.getFile()) ||
							deletedFiles.contains(event.getFile())) {
						it.remove();
					}
				} else if (event.getType() == FileSystemEventType.ADDED) {
					if (deletedFiles.contains(event.getFile())) {
						it.remove();
					}
				} else if (event.getType() == FileSystemEventType.REMOVED) {
					if (addedFiles.contains(event.getFile())) {
						it.remove();
					}
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
				// handle the events in another thread 
				Thread t = new Thread(() -> {
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
				t.setUncaughtExceptionHandler((thread, e) -> logger.error("Uncaught exception detected: {}", e.getMessage(), e)); //$NON-NLS-1$
				t.start();
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
        dataByKey.put(key, data);
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
                	key.reset();
                    key.cancel();
                    dataByKey.remove(key);
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
		for (WatchKeyData value : dataByKey.values()) {
			for (WatchKey key : value.keys) {
				key.cancel();
			}
		}
		dataByKey.clear();
		//System.err.println("FileSystemWatcher.unwatchPaths() - end");
	}

    /**
     * Closes the underlying watch service.
     */
    public void close() {
        this.stopped = true;
        watchThread.interrupt();
        try {
            if (watchService != null) {
            	watchService.close();
            }
        } catch (final IOException ioe) {
            // ignore
        }
    }

    private class WatchKeyData {
        private final Path path;
        private final FileSystemConfig config;
        private final List<FileSystemListener> listeners = new ArrayList<>();
        private final List<WatchKey> keys = new ArrayList<>();
		public WatchKeyData(Path path, FileSystemConfig config) {
			this.path = path;
			this.config = config;
		}
    }

}
