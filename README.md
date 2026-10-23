# Tandem

**Shared lists that work offline and merge without conflicts.** An Android app (Kotlin, Jetpack Compose, Room, WorkManager) plus a small sync server (Ktor, PostgreSQL), built around a conflict-free replicated data type (CRDT) with hybrid logical clocks.

Two people can edit the same grocery list on the subway with no signal, both rename the same item, and when they come back online both phones show the same list. No "conflict" dialogs and no lost edits beyond the one that was overwritten.

[![CI](https://github.com/Tendo505/tandem/actions/workflows/ci.yml/badge.svg)](https://github.com/Tendo505/tandem/actions/workflows/ci.yml)
[Download the APK](https://github.com/Tendo505/tandem/releases/latest) · Android 8.0+ · English and Bahasa Melayu

<p>
  <img src="docs/lists.png" width="260" alt="Overview of lists with progress and unsynced-change counts">
  &nbsp;
  <img src="docs/list.png" width="260" alt="A shared list after merging edits from two phones">
</p>

## How it works

```mermaid
flowchart LR
  subgraph Phone A
    UIA[Compose UI] --> RA[Repository]
    RA -->|op + winning value| DBA[(Room: fields, outbox)]
    DBA --> WA[SyncEngine / WorkManager]
  end
  subgraph Server
    API[Ktor API] --> LOG[(PostgreSQL: append-only op log per list)]
  end
  subgraph Phone B
    WB[SyncEngine] --> DBB[(Room)]
  end
  WA -- "POST ops (idempotent)" --> API
  WB -- "GET ops after seq N" --> API
```

* **Every edit is an op:** "set field F of item X to V at time T". Lists are maps of `(item, field) → value`, merged with **last-writer-wins per field**. Ticking an item on one phone and renaming it on another both survive, because they touch different fields.
* **Timestamps are hybrid logical clocks** (wall-clock millis, counter, device id). They are totally ordered, so every phone picks the same winner. A phone that has seen a remote edit always stamps its next edit later, even if its own clock is behind. This is tested with a phone whose clock is an hour slow.
* **Ordering uses fractional indexes**: moving an item writes one new position key between its neighbours. Nothing else is renumbered, so concurrent moves and inserts merge too.
* **The server never interprets ops.** It is an append-only log per list: devices push ops and pull "everything after sequence number N". All merge logic lives in one pure Kotlin module (`core`) shared by app and server, so they can't drift apart.
* **Offline-first app:** the UI reads only from Room. An edit applies locally and joins an outbox in a single transaction, and WorkManager uploads it once there's a network. While a list is open it also polls every few seconds, so other people's edits appear almost live.

### Details that matter

| Problem | What Tandem does |
| --- | --- |
| A response is lost after the server stored the ops | Pushes are idempotent (`UNIQUE(list_id, op_id)` with `ON CONFLICT DO NOTHING`). Ops leave the outbox only after a confirmed push. |
| Two uploads to the same list commit out of sequence order, so a poller skips an op forever | Writers take a row lock on the list (`SELECT … FOR UPDATE`), so sequence order equals commit order within a list. Different lists don't block each other. |
| A client advances its cursor past ops it never received | The cursor moves only to the last sequence number actually received, never to the server's `latestSeq`. Each page is applied in the same Room transaction as the cursor update. |
| A phone with its clock set a year ahead would win every conflict | The server rejects ops more than 5 minutes in the future, and the app shows the reason. |
| Two phones append at the same moment and get equal position keys | Ties sort by item id identically everywhere, and moves place an item correctly even next to tied keys. |
| Sync is interrupted halfway | Safe at any point: pushes can be retried and pulls are applied page by page. A sync can even be cancelled and replaced by a newer one. |

## Tests

| Module | What is covered |
| --- | --- |
| `core` (16) | HLC ordering, clock skew and counter overflow; fractional index ordering with 3,000 random inserts; a **convergence property test**: 200 runs of three phones making random concurrent edits, delivered in random order with duplicates, must end identical |
| `server` (6) | Push/pull, idempotent re-sends, pagination, validation, and **8 concurrent writers vs. a live poller with no missed or duplicated ops**. CI runs this on H2 and on real PostgreSQL 16 |
| `app` (15, Robolectric) | Two simulated phones against a fake server: offline edits merging, slow clocks, paging, lost responses, rejected ops, no-server mode; repository rules; a Compose UI test |

Also checked by hand on an emulator: edits made in airplane mode, concurrent edits from a second client, and automatic sync on reconnect, in both directions of the conflict.

```bash
./gradlew :core:test :server:test :app:testDebugUnitTest
```

## Run it

**Server** (Java 21). Without `DATABASE_URL` it uses in-memory H2:

```bash
./gradlew :server:run                  # http://localhost:8090
DATABASE_URL=postgres://user:pass@host:5432/tandem ./gradlew :server:run
docker build -t tandem-server . && docker run -p 8090:8090 tandem-server
```

Or deploy it to Render's free tier with the included [`render.yaml`](render.yaml) (web service plus PostgreSQL).

**App:** open the project in Android Studio, or `./gradlew :app:installDebug`. Debug builds talk to `http://10.0.2.2:8090` (your computer, as seen from the emulator). Release builds start with sync off: lists stay on the phone until you enter a server address in Settings. Share a list with the share button, and the other person pastes the message into **Join a shared list**.

### API

| Method | Path | |
| --- | --- | --- |
| `POST` | `/api/lists/{id}/ops` | `{"ops":[…]}` → `{"stored":n,"latestSeq":s}`. Max 500 ops per request, each validated. |
| `GET` | `/api/lists/{id}/ops?after=N&limit=500` | `{"ops":[{"seq":…,"op":{…}}],"latestSeq":s,"hasMore":bool}` |
| `GET` | `/health` | `ok` |

## Project layout

```
core/     pure Kotlin: Hlc, Op/Field model, Replica (LWW map), FractionalIndex, protocol DTOs
server/   Ktor app + OpStore (JDBC, HikariCP; PostgreSQL or H2)
app/      Android: Room (fields, outbox, lists), SyncEngine, SyncWorker, Compose screens
```

## Limits and next steps

* Lists are unlisted rather than private: anyone with a list code can read and edit that list. Accounts and per-list permissions would come next.
* Last-writer-wins works per field, so two people typing into the *same* item at the same time keep one version, not a merge of both. Character-level text merging (a sequence CRDT) would fix that.
* The phone keeps only the winning value per field, not the history, so storage stays small. The server keeps the full log; compacting old ops is future work.

MIT License
