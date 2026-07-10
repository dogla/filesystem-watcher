# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Fixed

- Single-file watches also fired for sibling files whose name merely starts with the watched
  name (`test.js` matched `test.js.tmp.12345` - the temp files of atomic writers), and directory
  watches matched same-prefix sibling directories (`dir` vs `dir2`). The path check is now
  component-wise.

- Event coalescing swallowed atomic file replacements: modern editors and tools (VS Code in some
  modes, Claude Code, many atomic writers) replace a file via *write temp + delete target + rename
  temp to target*. Both `DELETE` and `CREATE` of the target land in the same poll batch, cancelled
  each other in the duplicate filter, and **no event was delivered at all** - the replacement was
  invisible to listeners. Such a batch now surfaces as a single `MODIFIED` event (the file still
  exists afterwards). Transient temp files (created and deleted within one batch) still net to
  nothing. Note for testing: `java.nio.file.Files.move` happens to emit an extra `ENTRY_MODIFY`
  on Windows which masked the bug; the affected writers do not.
