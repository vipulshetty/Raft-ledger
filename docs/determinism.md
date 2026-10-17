# Determinism

RaftLedger's consensus and ledger cores are event-driven state machines. They
do not read the host clock, access files or sockets, start threads, or obtain
uncontrolled randomness. A runtime supplies a `Tick` event and executes the
effects returned by `step` in order.

The simulator uses the same rule: virtual time, a fixed random-generator
algorithm, and an event queue ordered by virtual time and insertion sequence.
The same seed therefore produces the same scenario and event trace.

ArchUnit tests in `raft-core`, `ledger-core`, and `sim` reject dependencies on
wall-clock APIs, I/O, threads, concurrency utilities, random generators, and
UUID generation. If a core needs a value that would normally be nondeterministic
(for example, a timeout or timestamp), the value must be supplied in an input
or stamped into a command before replication.
