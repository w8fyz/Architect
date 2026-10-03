# Architect Migrations — from dev to prod

This guide covers the versioned migration system: how a schema change you made in
development becomes a reviewed SQL file, and how that file is applied to production
deliberately instead of letting `hbm2ddl=update` mutate the schema at boot.

The model is simple:

- **Your entity classes are the source of truth.** You change them freely in dev.
- **Migration files are the audit trail.** Each one is `V<version>__<description>.sql`,
  numbered, committed to the repository, and applied exactly once per database.
- **A history table records what ran.** `architect_schema_history` stores each applied
  migration with its checksum, so every environment knows exactly where it stands.

```
dev: edit entities  →  diff  →  review the generated .sql  →  commit
prod: verify  →  apply
```

## Wiring the CLI into your application

Architect has no `main` of its own — only your application knows which entities to
register. Add a dispatch at the top of your own `main`:

```java
public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("migrate")) {
        Architect architect = bootArchitectOnly(); // credentials + entities, no server
        System.exit(MigrationCli.run(architect, Path.of("migrations"),
                                     Arrays.copyOfRange(args, 1, args.length)));
    }
    startServer();
}
```

Boot Architect exactly the way the application normally does (same credentials, same
`addEntityClass` calls), just without starting the rest of your app. Every command below
is then available as `java -jar app.jar migrate <command>`.

> In production, run the application itself with `hbm2ddlAuto = "validate"` (or
> `"none"`). That is the point of the system: the schema only changes when a migration
> runs, never as a side effect of booting.

## The day-to-day loop

### 1. Change your entities in dev

Work as usual — add fields, entities, enum constants. Locally, `hbm2ddlAuto = "update"`
keeps your dev database in sync while you iterate.

### 2. Generate the migration

```
migrate diff add_invoice_table --shadow-url jdbc:postgresql://localhost:5432/myapp_shadow
```

This computes the SQL that brings the schema up to your entity model and writes it as
the next numbered file, e.g. `migrations/V7__add_invoice_table.sql`.

The **shadow database** is a scratch database that gets **wiped and rebuilt from your
committed migration files** before the comparison. That makes the diff a pure function
of the repository: it is exactly "what V1..V6 produce" versus "what the entity model now
says", independent of whatever your dev database happens to contain. Two people on the
same commit get the same file.

Requirements for the shadow:

- It must be a database that **exists** and that you are happy to see **emptied** —
  create a dedicated `myapp_shadow` once and reuse it forever.
- It must not be the application's own database. The tool refuses to wipe the URL the
  application is configured with (compared with query parameters stripped and default
  ports normalised), but do not lean on the guard — point it at a scratch database.
- Credentials default to the application's own; override with `--shadow-user` /
  `--shadow-password` if the scratch database uses different ones.

Use `--dry-run` to print the migration to stdout instead of writing the file.

### 3. Review the file

Open the generated file before committing. It has up to three sections:

- **Schema additions** — `CREATE TABLE`, `ADD COLUMN`, indexes, foreign keys. Generated
  by Hibernate's own schema migrator, ready to apply as-is.
- **Enum CHECK constraints** (PostgreSQL) — regenerated for any enum whose Java
  definition no longer matches the database constraint. Each is a `DROP CONSTRAINT IF
  EXISTS` + `ADD CONSTRAINT`, so re-applying is safe. This matters: without it, adding
  an enum constant works everywhere in dev and then fails every production INSERT
  carrying the new value, because `validate` never repairs CHECK constraints.
- **DESTRUCTIVE — commented out on purpose** — dropped tables/columns and type changes
  (including a changed length or precision, which can truncate or round stored values).
  These are never emitted live, because a machine cannot tell a removal from a rename,
  and dropping a column destroys data irreversibly. Read each line and uncomment what
  you actually mean.

When a drop and an add happen in the same table, the file (and the CLI summary) flags
the pair as a **possible rename**:

```
-- Possible renames (a drop and an add of the same type in one table
-- are indistinguishable from a rename):
--   users: login -> username  ? If so, replace both statements with a single ALTER ... RENAME COLUMN.
```

If it really is a rename, delete both generated statements and write
`ALTER TABLE users RENAME COLUMN login TO username;` instead — that preserves the data.

### 4. Commit, then apply in production

```
migrate status    # what is applied, what is pending
migrate verify    # history and files agree?  exit 2 if not
migrate apply     # run every pending migration, in version order
```

`apply` runs `verify` first and refuses if anything is wrong (`--force` overrides, if
you understand the consequences). Each migration runs in its own transaction together
with its history row, so a failure cannot leave a script half-recorded. On PostgreSQL
the migration's own statements roll back too; MySQL, MariaDB and H2 commit DDL
implicitly, so a mid-file failure there can leave partial DDL applied.

`apply --dry-run` lists what would run without applying anything (it still creates the
empty history table if it does not exist yet).

## Command reference

```
diff <desc>           generate the next V<n>__<desc>.sql from a schema comparison
  --shadow-url <u>    scratch database, WIPED and rebuilt from the migration files
                      (the normal, reproducible mode)
  --target-url <u>    an existing database, read as-is and never written to
  --shadow-user <u>   credentials for the reference database, if they differ
  --shadow-password <p>
  --dry-run           print the migration instead of writing it

status                applied and pending migrations
verify                check history against the files on disk
apply [--dry-run] [--force]
baseline [version]    snapshot the current schema and record it as applied
                      WITHOUT running it (adopting an existing database)
enums                 print enum CHECK constraints for the entity model
snapshot              print a full schema snapshot, write nothing
```

Exit codes: `0` ok, `1` error, `2` verification problems — usable directly in CI.

## Adopting an existing database

If production already exists (built over time by `hbm2ddl=update`), you cannot replay
migrations into it — there are none yet. Establish a starting point once:

```
migrate baseline        # writes V1__baseline.sql and records it as applied
```

If `V1__baseline.sql` is already committed (another database was adopted at the same
point), it is recorded as is and never rewritten; `baseline` refuses a version that
another migration file already uses.

The snapshot is **not executed** — it documents the schema as it already stands. From
then on, every change goes through the normal `diff` → `apply` loop, and a fresh
environment (a new developer's machine, a test database) can be built from V1 upward.

## Catching up a drifted database

If someone hot-fixed production by hand, the committed migrations no longer describe
reality. To see and absorb the drift, diff against the real database instead of a
shadow:

```
migrate diff catch_up_prod --target-url jdbc:postgresql://prod-host:5432/myapp
```

With `--target-url` the reference is used **as-is and never written to**. The generated
file contains whatever separates that database from the entity model — including the
drift, which is the point. Review it like any other migration.

## CI: keep the model and the migrations honest

Because the shadow diff is reproducible from the repository alone, CI can enforce that
nobody changes an entity without generating the migration:

```
migrate diff ci_check --shadow-url $SCRATCH_URL --dry-run
```

The command exits 0 either way, so check its output: a `No differences` line means the
committed migrations produce the schema the entity model expects (within what the diff
detects — see *Known limitations*); anything else means a migration is missing. For
example:

```
migrate diff ci_check --shadow-url $SCRATCH_URL --dry-run | grep -q "No differences"
```

## Safety model, in one place

- Nothing in the CLI ever drops anything from the application's database. The only wipe
  is of the shadow, and the shadow refuses to be the application's own URL.
- Generated destructive statements are always commented out.
- Applied files are checksummed (SHA-256, line endings normalised); editing or deleting
  an already-applied file is reported by `verify` and blocks `apply`.
- A migration numbered before something already applied is reported as out-of-order —
  it would silently be skipped on databases that are ahead.
- Two files with the same version are reported by `verify` and block `apply`, unless both
  are already recorded (by filename). With the same spelling (`V7__a.sql` and `V7__b.sql`)
  only one of them can ever be recorded: once one is applied, the other would be skipped
  for good. With different spellings (`V1` and `V01`) both are applied, in an order that
  depends on the spelling. Renumber the pending file.
- The history table (`architect_schema_history`) is created automatically and never
  dropped by the CLI. `MigrationManager.clearDatabase` (the GUI's Clear tab) drops every
  table, the history table included.

## Known limitations

- **Enum CHECK constraints are PostgreSQL-only.** Other dialects get an empty section.
- **Destructive suggestions use PostgreSQL syntax** (`DROP ... CASCADE`,
  `ALTER COLUMN ... TYPE ...`), except length/precision changes, which come from Hibernate
  in the dialect's own syntax. Identifiers are quoted for the dialect (backticks on
  MySQL/MariaDB). They are commented out; adapt them if you run MySQL/MariaDB.
- **Not detected:** nullability changes, and indexes, unique keys or foreign keys removed
  from the model. Write those migrations by hand.
- **Statements that cannot run inside a transaction** (PostgreSQL `CREATE INDEX
  CONCURRENTLY`, `VACUUM`) cannot be applied: every migration runs in one transaction.
- **Sequences are not diffed for removal.** A sequence left behind by a dropped
  entity is harmless but lingers; drop it by hand if you care.
- **The shadow database must already exist.** The tool empties it; it does not create it.
- `--shadow-password` on the command line is visible in the process list; prefer
  letting it default to the application's credentials.
- A brand-new enum column added to an existing table can end up with two equivalent
  CHECK constraints (Hibernate's inline one plus the regenerated one). Harmless, and
  the next diff converges.

## GUI

For interactive use in development, the Swing tool offers the same inspection features:

```java
MigrationToolGUI.open(architect, Path.of("./migrations"));
```
