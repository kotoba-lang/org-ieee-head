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
and compares bytes *and exit status* against `/usr/bin/head`. Twelve cases,
all identical.

The boundaries are deliberate: `-n 1` is the low edge, `-n 20` is the file's
exact line count, and `-n 25` is past it. A walk that stops one early or one
late fails `-n 20` and not `-n 25`.

Verified to fail as well as pass: a default of 5 instead of 10 fails exactly
one case, an off-by-one in the newline walk fails three, and emitting nothing
when the file is shorter than N fails four.

## The error paths are not matched, and that is a capability gap

`/usr/bin/head` refuses `-n 0` with `head: illegal line count -- 0` and
reports a missing operand with `head: FILE: No such file or directory` —
both on **stderr**, both exiting 1.

There is no stderr capability. `:io/write` (wire 37) is fd 1 and only fd 1,
so those bytes cannot be produced. This exits 1 for `-n 0` and prints
nothing: the status without the message.

The suite asserts the success paths and **says which cases it does not
cover**, rather than covering them wrongly. A `:io/write-error` capability is
the next gap this family needs.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37). Fuel, the string
arena, the grant and the filesystem scope are all constants of the packaged
binary — a caller cannot raise any of them.

## What this is not

One operand. `/usr/bin/head` with several prints `==> name <==` banners,
which this does not. No `-c` (bytes), and no reading standard input.
