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

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The configuration for a file system path.
 *
 * @author Dominik Glaser
 * @since 1.0
 */
public class FileSystemConfig {
	
	private int maxDepth = 1;
	private Predicate<Path> filter;
	private Set<FileSystemEventType> allowedEventTypes;
	
	/**
	 * Returns the maxDepth.
	 *
	 * @return the maxDepth
	 */
	public int getMaxDepth() {
		return maxDepth;
	}

	/**
	 * Sets the maxDepth.
	 *
	 * @param maxDepth the maxDepth to set
	 *
	 * @return the instance itself
	 */
	public FileSystemConfig withMaxDepth(int maxDepth) {
		if (maxDepth <= 0) {
			throw new IllegalStateException("Only values greater than 0 are allowed."); //$NON-NLS-1$
		}
		this.maxDepth = maxDepth;
		return this;
	}

	/**
	 * Returns the filter.
	 *
	 * @return the filter
	 */
	public Predicate<Path> getFilter() {
		return filter;
	}

	/**
	 * Sets the filter.
	 *
	 * @param filter the filter to set
	 *
	 * @return the instance itself
	 */
	public FileSystemConfig withFilter(Predicate<Path> filter) {
		this.filter = filter;
		return this;
	}

	/**
	 * Returns the allowedEventTypes.
	 *
	 * @return the allowedEventTypes
	 */
	public Set<FileSystemEventType> getAllowedEventTypes() {
		return allowedEventTypes;
	}

	/**
	 * Sets the allowedEventTypes.
	 *
	 * @param allowedEventTypes the allowedEventTypes to set
	 *
	 * @return the instance itself
	 */
	public FileSystemConfig withAllowedEventTypes(FileSystemEventType... allowedEventTypes) {
		this.allowedEventTypes = null;
		if (allowedEventTypes != null) {
			this.allowedEventTypes = new HashSet<>();
			for (FileSystemEventType eventType : allowedEventTypes) {
				if (eventType != null) {
					this.allowedEventTypes.add(eventType);
				}
			}
		}
		return this;
	}
	/**
	 * Sets the allowedEventTypes.
	 *
	 * @param allowedEventTypes the allowedEventTypes to set
	 *
	 * @return the instance itself
	 */
	public FileSystemConfig withAllowedEventTypes(Set<FileSystemEventType> allowedEventTypes) {
		this.allowedEventTypes = allowedEventTypes;
		return this;
	}

	/**
	 * This method checks the available event types ({@link #withAllowedEventTypes(Set)}) if the given event type is part of the set.
	 * If the event types set is not determined all event types are valid.
	 * 
	 * @param eventType the event type to check
	 * 
	 * @return <code>true</code> if the given event type is allowed.
	 */
	public boolean isEventAllowed(FileSystemEventType eventType) {
		if (allowedEventTypes != null) {
			return allowedEventTypes.contains(eventType);
		}
		return eventType != null;
	}
	
}
