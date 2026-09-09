# kotoba-lang/org-ieee-head — POSIX `head`, as a Kotoba command binary

`head` from IEEE Std 1003.1 for one operand, written in `.kotoba` and
compiled to a standalone native executable.

```sh
./head FILE          # the first 10 lines
./head -n 3 FILE     # the first 3
```

## "The first N lines" is bytes, not lines

It is **the bytes up to and including the Nth newline**, and nothing is
added. Measured 2026-09-10 against `/usr/bin/head` on a file holding `x\ny` —
two lines, no trailing newline — the answer is `x\ny`, **three** bytes. An
implementation that reasoned in "lines" and re-joined them with newlines
would answer four, and would pass every other case in this suite.

A file with fewer than N newlines is emitted **whole and unchanged**.

## Measured against the system utility

`test/head_test.cljs` compiles the guest, packages it, **runs the binary**,
and compares bytes *and exit status* against `/usr/bin/head`. Fifteen cases,
all identical.

The boundaries are deliberate: `-n 1` is the low edge, `-n 20` is the file's
exact line count, and `-n 25` is past it. A walk that stops one early or one
late fails `-n 20` and not `-n 25`.

Verified to fail as well as pass: a default of 5 instead of 10 fails exactly
one case, an off-by-one in the newline walk fails three, and emitting nothing
when the file is shorter than N fails four.

## One error path is matched now, and one still is not

`-n 0` is matched **byte for byte on stderr**, since `:io/write-error`
(wire 39) landed:

```
head: illegal line count -- 0
```

exit 1, nothing on stdout. `-n 00` and `-n x` are in the suite because head
echoes the count **as given** rather than re-rendering it.

That comparison discriminates, and it is worth saying how. Reverting to what
this did before wire 39 — exit 1 and say nothing — fails three cases **with
identical stdout and identical exit status on both sides**:

```
FAIL ["-n" "0" "three"] -> "" but /usr/bin/head says "" exits [1 1]
```

Changing the message's two dashes to one fails the same three. Neither would
have been visible to a suite that compared only stdout and status.

**A missing operand is still not matched, and stderr does not fix it.** The
read capability **traps** on a path it cannot serve rather than answering a
result, so the guest never gets control back to report anything — measured
2026-09-10: `SIGILL`, exit 120, where `/usr/bin/head` writes
`head: PATH: No such file or directory` and exits 1. Reporting that needs the
capability to answer `[:result T E]`, which is a change to its contract and
not to this program.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37). Fuel, the string
arena, the grant and the filesystem scope are all constants of the packaged
binary — a caller cannot raise any of them.

## What this is not

One operand. `/usr/bin/head` with several prints `==> name <==` banners,
which this does not. No `-c` (bytes), and no reading standard input.
