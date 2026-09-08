# Changelog

## Unreleased

- Add `oscope.embedded.query`, a bounded background query facade that keeps
  blocking chDB/JDBC work off Jolt UI fibers and joins its owned OS thread
  before a shared embedded source can be retired.
