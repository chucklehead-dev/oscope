# Changelog

## Unreleased

- Bound the standalone HTTP dispatcher to its configured workers and waiting
  capacity despite Jolt 0.8.3's advisory ThreadPoolExecutor queue model; excess
  connections are closed before handler execution, and ordered shutdown drains
  admitted work before terminating the owned pool.
- Render singleton and extreme finite numeric chart domains without NaN or
  Infinity coordinates. One-sample lines now receive a visible point fallback,
  while one-sample areas explicitly collapse to a baseline-to-value segment.
- Pin the bounded jolt-chdb Durable retry implementation in the application and
  native qualification workflows, and expose separate writer-control and
  S3-transport deadline/backoff settings through the standalone collector
  environment.
- Follow the retry-aware Durable aspect epoch and terminal operation arities so
  hosted history validation observes each writer operation exactly once.
- Bound long-running standalone Durable recovery chains by checkpointing every
  configured number of acknowledged OTLP batches; failed checkpoints remain
  due and cannot be silently replaced by another WAL flush. Deterministic
  two-worker coverage proves cadence selection cannot double-checkpoint a
  shared boundary.
- Build standalone Durable writer configuration through jolt-chdb's validated
  dbspec constructor, so role and storage mistakes fail before collection starts.
- Qualify the Durable collector against jolt-chdb's owned worker and heartbeat
  threads, and require its woven lifecycle trace to renew before release.
- Add `oscope.embedded.query`, a bounded background query facade that keeps
  blocking chDB/JDBC work off Jolt UI fibers and joins its owned OS thread
  before a shared embedded source can be retired.
