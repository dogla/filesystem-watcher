
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dogla/filesystem-watcher.svg?label=Maven%20Central)](https://search.maven.org/artifact/io.github.dogla/filesystem-watcher)

# filesystem-watcher

Abstraction layer around `java.nio.file.WatchService` that provides a more programmer friendly API to watch file system events.

# Dependencies

- Java 8
- `org.slf4j:slf4j-api:1.7.32`

# Setup

To use this library you can use the corresponding maven dependency:

```xml
    <dependency>
        <groupId>io.github.dogla</groupId>
        <artifactId>filesystem-watcher</artifactId>
        <version>1.0.0</version>
    <dependency>
```

# Usage

## Watch files

This sample creates a file system watcher and registers for the file `C:\myapp\config.json` a listener that will be called if the file is created, modified or deleted.

```java
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
```

If you are only interested in special event types you could provide a `FileSystemConfig` with the appropriate types.

```java
FileSystemWatcher fsWatcher = new FileSystemWatcher("my-filesystem-watcher");
FileSystemConfig config = new FileSystemConfig().withAllowedEventTypes(FileSystemEventType.MODIFIED);
fsWatcher.watchPath(new File("C:\\myapp\\config.json"), (event) -> {
    System.out.println("File modified");
}, config);
```

# Watch directories

This sample creates a file system watcher and registers for the directory `C:\myapp\` a listener that will be called if the directory is created, modified or deleted or any of the direct subdirectories or subfiles will be created, modified or deleted.

```java
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
```

If you are also interested in further subfiles and subdirectories you can specify the maximum depth to which the file changes should be detected. Per default only the direct children will be considered (max depth of 1). If you want one more level you have to set the max depth in the config via `setMaxDepth(int)` to `2` or to any depth you need.

```java
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
```

# Filter

You could also specify a `Predicate`  that will filter out all undesired files. The event listener will only be called for those files that match the given filter.

```java
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
```
