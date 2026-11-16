# Telegram Drive integration for Ortho Tips

Extract this archive over the root of the Ortho Tips repository. Every path
mirrors the existing tree, so files either land as new sources or replace the
file they are meant to replace.

```
unzip ortho-tips-telegram-drive.zip -d /path/to/Ortho-Tips
```

---

## 1. What this adds

Telegram Drive is an **optional** second storage backend. Local storage keeps
working exactly as before and remains the default. Switching backends never
moves or deletes a byte — migration is a separate, explicit action.

| Area | Added |
| --- | --- |
| Storage | `StorageProvider` abstraction with local and Telegram implementations |
| Telegram | TDLib client ported from TeleDrive (auth, drive channel, transfers) |
| Security | Keystore-wrapped keys, chunked AES-256-GCM, SHA-256 verification |
| Persistence | Room v5 — three new tables, purely additive |
| Sync | Manifest pull, transfer planning, tombstones, whole-database snapshots |
| Background | WorkManager queue worker + periodic sync with constraint handling |
| UI | 11-step setup wizard, Telegram Drive settings section, diagnostics screen |
| Cutover | `PatientRepository` and the media read paths now go through the abstraction |
| Tests | JVM tests for the cipher and wire format; instrumented tests for v5 |

---

## 2. Files in this archive

### Modified (replace existing)

```
gradle/libs.versions.toml                                    TDLib, WorkManager, documentfile
settings.gradle.kts                                          JitPack, scoped to com.github.tdlibx
app/build.gradle.kts                                         new deps, jniLibs packaging, Room schema export
app/proguard-rules.pro                                       TDLib + serialization keep rules
app/src/main/AndroidManifest.xml                             network + notification permissions
app/src/main/java/com/example/MainActivity.kt                three new routes
app/src/main/java/com/example/OrthoApplication.kt            drive session restore on cold start
app/src/main/java/com/example/di/AppModule.kt                drive object graph
app/src/main/java/com/example/data/local/OrthoDatabase.kt    v5 + MIGRATION_4_5
app/src/main/java/com/example/navigation/OrthoNavigation.kt  three destinations
app/src/main/java/com/example/ui/screens/main/MainScreen.kt  one parameter, passed through
app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt   one "Cloud storage" row
app/src/main/java/com/example/data/repository/PatientRepository.kt    media routed through the gateway
app/src/main/java/com/example/viewmodel/ImageAnnotationViewModel.kt   resolve before editing pixels
app/src/main/java/com/example/viewmodel/PatientDataTransferViewModel.kt  prefetch before export
```

The five screen/activity/navigation files were patched programmatically from
your originals rather than retyped, so nothing outside the named change differs
by a single character.

### New

```
data/telegram/     TelegramModels, TelegramAuthState, TelegramClient,
                   TdLibTelegramClient, DriveLog
data/crypto/       KeyVault (KeystoreManager, WrappedKeyRepository, CryptoKeys,
                   KeystoreTdlibKeyProvider), StreamCrypto, Hashing
data/storage/      StorageModels, StorageProvider, LocalStorageProvider,
                   TelegramStorageProvider, StorageProviderRegistry,
                   PatientMediaGateway, DriveMediaInterceptor
data/sync/         DriveManifest + ManifestCodec, DatabaseSnapshot, SyncEngine
data/local/        entity/SyncEntities, dao/SyncDaos
data/prefs/        TelegramDriveRepository
work/              DriveWorkers (TransferQueueWorker, SyncWorker, SyncScheduler)
viewmodel/         TelegramDriveViewModels (setup + settings)
ui/screens/storage/ TelegramSetupWizardScreen, TelegramDriveSettingsScreen,
                   StorageDiagnosticsScreen
test/              DriveCryptoAndFormatTest
androidTest/       DriveSchemaTest
```

---

## 3. Decisions worth knowing about

**No Hilt.** Ortho Tips uses a hand-rolled `AppModule` with `by lazy`
properties, reached through `OrthoApplication`. TeleDrive is Hilt throughout.
Introducing Hilt would mean annotating every ViewModel, Activity and factory in
your app — a large change with real regression risk, for no benefit here. All
ported classes had their `@Inject`/`@Singleton` annotations removed and are
constructed in `AppModule` instead. Workers reach the graph through
`(applicationContext as OrthoApplication).appModule`.

**minSdk stays 24, Java stays 11.** TeleDrive is minSdk 26 / Java 17. Raising
either would drop existing users or churn the build for no gain; the TDLib AAR
and everything ported here run fine at 24.

**Compose stays on BOM 2024.09.00.** All new UI is written against Material 3
1.3 APIs only — no wavy progress indicators, no `ButtonGroup`, nothing newer.

**Local paths are unchanged.** `LocalStorageProvider` resolves every
`StorageKey` to the exact path `PatientMediaStorage` already produces
(`filesDir/patients/{id}/images/...`). Existing installs read and write the same
bytes in the same places; there is no migration step and no risk window.

**The drive channel has its own marker.** TeleDrive claims channels titled
`TeleDrive` with `#teledrive-storage`; this uses `Ortho Tips Drive` with
`#orthotips-drive`, so both apps can run on one account without adopting each
other's storage.

**Encryption is on by default, unlike TeleDrive.** Patient imaging is medical
data, so uploads are AES-256-GCM encrypted before leaving the device, remote
filenames are opaque hashes, and captions are encrypted too. The plaintext
SHA-256 travels inside the manifest so integrity is verifiable without
decrypting.

**Deletion goes through tombstones, never through absence.** "Not in the local
set" is indistinguishable from "not downloaded yet"; treating those the same is
how sync engines destroy data. `delete()` is always soft — bytes survive until
an explicit `purge()` past the retention window.

**Record sync uses a generic table snapshot.** `DatabaseSnapshot` walks
`sqlite_master` and cursor metadata rather than typed DAOs, so it covers every
table you have now and every table you add later, and cannot drift out of step
with the entity classes. Restore is `INSERT OR REPLACE` in one transaction:
rows present locally but absent from the snapshot are kept.

---

## 4. First run

1. Build and install. Nothing changes — the backend is `LOCAL`.
2. Settings → **Cloud storage → Telegram Drive** → *Run Telegram Drive setup*.
3. The wizard needs an API id and hash from <https://my.telegram.org> →
   *API development tools*. These are the user's own credentials; they are
   sealed with the Android Keystore before they touch disk and are never sent
   anywhere except Telegram.
4. The backend only switches to `TELEGRAM` at the initial-sync step. Backing out
   before that leaves everything as it was.

---

## 5. Before you ship

These are the things I could not verify from here, and they are the first places
to look if the build complains.

- **I could not compile this.** There is no Android SDK, Gradle or Google Maven
  access in the environment I wrote it in. Expect to resolve a small number of
  import or signature issues on the first build. The logic is sound; the
  mechanical details are unverified.
- **Check the TDLib artifact resolves.** `com.github.tdlibx:td:1.8.56` comes
  from JitPack. If your CI runs behind a proxy, whitelist `jitpack.io`.
- **APK size.** TDLib ships four ABIs of prebuilt `.so`, roughly 40 MB
  uncompressed. TeleDrive handles this with ABI splits; I deliberately did not
  add splits because they rename the output file and your `main.yml` uploads a
  single fixed artifact. Add them when you are ready to change the workflow.
- **`fallbackToDestructiveMigration(true)` is still in `OrthoDatabase`.** I left
  it exactly as it was, since removing it changes failure behaviour on existing
  installs. It does mean a schema mismatch wipes patient data — worth revisiting
  now that a drive backup exists to restore from.
- **Room schema export is now on.** After the first v5 build, `app/schemas/`
  will contain `5.json`. From that point a real `MigrationTestHelper` test can
  be written for v5 → v6. It cannot be written for 4 → 5 because no `4.json` was
  ever exported.

---

## 6. How the cutover works

`PatientMediaGateway` is the seam. `PatientRepository` gained an optional third
constructor parameter; when it is null — as in every existing unit test — the
repository behaves exactly as it did before, hard deletes included.

- **Saving.** `saveImage` and `saveDocument` queue an upload after the row is
  committed, never before, so a transfer cannot reference a record the database
  does not yet have. Images and thumbnails are queued separately: a second device
  needs thumbnails for the grid long before anyone opens an original.
- **Deleting.** `deleteImage`, `deleteDocument`, `deletePatient(s)` now soft-delete
  through the gateway — files move to a recoverable `.trash`, and a tombstone is
  recorded so the removal propagates instead of the drive resurrecting it on the
  next pull.
- **Reading.** `resolveImageFile`, `resolveThumbnailFile` and `resolveDocumentFile`
  return a readable `File`, fetching from the drive when the local copy is gone.
- **Display.** `DriveMediaInterceptor` is installed on the app-wide Coil loader via
  `OrthoApplication : ImageLoaderFactory`. Every existing `AsyncImage` call site
  renders drive-only images with no change to a single screen; the interception
  happens below them. The common path costs one `File.exists()` check.
- **Editing pixels.** `ImageAnnotationViewModel` resolves before rotating or
  cropping — mutating a missing file would destroy the image — and re-saves the
  row afterwards so the altered bytes take a new revision in the drive.
- **Exporting.** `PatientDataTransferViewModel` warms the cache before zipping,
  because the exporter skips files it cannot find and would otherwise write a
  silently incomplete archive.

---

## 7. Fixes after the first device run

Two bugs surfaced on the first real install, both at step 6 of the wizard.

**The wizard deadlocked on "Create the drive".** `canAdvance` gated that step on
`chatId != null`, but `chatId` is produced by the very work that `advance()`
runs — and `advance()` returned early when `canAdvance` was false. The step could
never start, so Continue stayed disabled forever with the previous step's status
text ("Checking code…") still on screen.

Fixed by separating the two concerns. `SetupStep.isAutomatic` marks the five
steps that do work rather than collect input; those now run from a
`LaunchedEffect(state.step)` as soon as they appear, record themselves in
`completedSteps`, and gate Continue on that instead of on their own output.
`advance()` is pure navigation for them. A failure now shows a **Try again**
button rather than a dead end, and `goTo()` is the single place that changes
step so stale status text cannot carry over.

**Drive discovery was quadratic.** `ensureStorageChat` retried `findStorageChat`
eight times, and each attempt fell through to walking both chat lists with one
`GetChat` per chat — up to sixteen thousand sequential requests on an account
with a normal number of chats. It would have looked like a hang even once the
deadlock was cleared, and would likely have tripped Telegram's flood limits.

Split into `searchStorageChat` (two title searches plus lookups for whatever
matched — cheap, and the one that is retried while the chat list syncs) and
`scanChatListsForStorageChat` (the expensive walk, run exactly once, and only as
a fallback for a drive channel that was renamed). Retries dropped from eight to
four now that each is cheap.

Also corrected: the connection-test step claimed to be "sending a test object"
when it only queried account limits. It now says what it does and reports the
signed-in account.

### Flow transparency violation on the record snapshot

Symptom: "Record snapshot failed: Flow exception transparency is violated" after
the drive was otherwise working and uploading.

`SyncEngine.pushDatabaseSnapshot` and `TelegramStorageProvider.materialize` both
ended their transfer flows with `first { it is Completed || it is Failed }`.
`first` stops collection by throwing `AbortFlowException` — which is an
`Exception`, so it was caught by the provider's own `catch (e: Exception)`, which
then tried to `emit(Failed(...))` into a flow that had already been torn down.
kotlinx correctly rejects that.

Fixed twice over. The call sites now use `Flow<TransferProgress>.awaitTerminal()`,
which collects normally — these flows end on their own right after the terminal
event, so aborting was never necessary. And both provider flows now catch
`CancellationException` first and rethrow, so no future collector that stops
early can turn into a bogus `Failed` event. A genuinely failed snapshot upload
now raises `StorageException` instead of being reported as success.

### Restore returned nothing

Three faults stacked up, visible in the device log as `Snapshot exported: 0 rows`
followed by `Snapshot imported: 0 rows into 0 tables`.

**Empty snapshots buried the good one.** Every sync exported the local database
and uploaded it under a fresh timestamped key. On a device whose database is
empty — a reinstall, or a restore that has not happened yet — that wrote a
snapshot containing nothing, newer than the one holding the real records.
`pullDatabaseSnapshot` then picked the newest and imported zero rows. Seven empty
snapshots accumulated this way in one session.

`pushDatabaseSnapshot` now refuses to upload an empty snapshot when the drive
already holds one with records. An empty local database is never evidence that
records were deleted, only that this device has not been restored yet.

**Restore picked by date alone.** It now walks snapshots newest-first and takes
the first that actually contains rows, verifying with `inspect()` before
importing. Snapshot keys carry their row count — `ortho-db-{millis}-r{rows}.json`
— so most candidates are ruled out without a download, and the count survives a
manifest rebuild because the name is what Telegram stores.

`cleanUp` also prunes empty snapshots, keeping the newest as a marker. It only
ever touches snapshots whose key says they hold nothing, so a real backup cannot
be pruned by it.

**Unreadable objects were silently skipped.** `isLocked` only checked whether *a*
content key existed, missing the case that actually occurs: a key exists, but a
different one than the object was sealed with, so every decrypt fails quietly.
`ManifestCodec.inspect` now separates "not ours" from "ours but unreadable", the
sync report counts the latter, and it is surfaced in diagnostics and as a sync
error instead of vanishing.

### Every import rolled back on a foreign key

Symptom: restore reported finding a snapshot with 8 rows, then the very next
export read `Every table read back empty`. Device logs showed
`Importing into …/ortho_tips.db` with no matching `Snapshot imported:` line —
the import started and never finished.

`tableNames()` returns tables alphabetically, which puts `patient_documents` and
`patient_images` ahead of `patients`. Both carry a foreign key to it, Room
enforces foreign keys, so the first child row failed, the transaction rolled
back, and nothing was ever written. The exception was then swallowed by a
`runCatching` in `migrateDriveToLocal`, so the UI reported success over an empty
database.

Three changes. The import now issues `PRAGMA defer_foreign_keys = TRUE` inside
the transaction, so checks happen at commit when every table is present.
`insertionOrder()` writes parents before children regardless, as belt and braces.
And the restore path no longer swallows failures: a per-row catch names the
offending table, and `migrateDriveToLocal` reports how many records were actually
restored instead of assuming.

---

## 8. Local-first with selective offload

The storage model changed after real use. The device is always the working copy;
the drive is a backup you can lean on to reclaim space, not an alternative place
to keep things.

**Sync frequency.** `SyncFrequency` replaces the old automatic/manual toggle:
`MANUAL`, `ON_CHANGE`, `DAILY`, `WEEKLY`, `MONTHLY`. Only the last three schedule
a periodic worker; `ON_CHANGE` is driven by saves as they happen, and `MANUAL`
waits for the surgeon. Switching to `ON_CHANGE` also triggers a catch-up sync, so
anything saved under a slower schedule is not left behind.

**Per-patient backup state.** `PatientRepository.driveStatus(patientId)` returns a
`PatientDriveStatus`: how many files are verifiably in the drive, whether a
snapshot newer than the patient's last edit exists, and how many local bytes
could be reclaimed. "Verifiably" means a live manifest row whose plaintext
SHA-256 matches the bytes on disk — a row alone is not enough, because an edited
radiograph keeps its path and a stale row would call it safe.

**Offload is not delete.** `PatientMediaGateway.offload()` removes full-size local
bytes and nothing else: no tombstone, the manifest row stays live, database rows
are untouched, and thumbnails stay on the device so the gallery still works.
Opening an offloaded image fetches it back through `resolve()`, which the Coil
interceptor already routes. Anything not verified as backed up is skipped, so a
half-finished upload can never cost the only copy of an image.

The distinction is load-bearing: `deleteImage` propagates a tombstone and removes
the drive copy, `offload` does not. The confirmation dialog says so in as many
words, because the two actions are one tap apart.

**Where it appears.** `PatientDriveStatusCard` is one `item { }` in the patient
info tab. It reads its own state rather than taking parameters, so nothing had to
be threaded through the navigation graph, and it renders nothing at all when the
drive is off — the screen is byte-identical for anyone not using it.

### Records were never snapshotted on save

Symptom: a patient's files upload and show "1 of 1 files", but the card stays on
"Backup in progress — record not yet sent" forever, and restoring returns
nothing.

The snapshot was only pushed inside `sync()`. A patient created between two sync
runs existed on the phone and nowhere else, and under a weekly or monthly
schedule that window was effectively permanent. Device logs showed it plainly:
a patient created at 21:18, deleted at 21:20, and every snapshot before and after
holding zero rows — the exports were honest, there was simply never a snapshot
taken while the patient existed.

`PatientMediaGateway.onRecordsChanged()` is now called from every record
mutation — `savePatient`, `setArchived`, `deletePatient(s)`, `saveTimelineEvent`,
`deleteTimelineEvent`, `saveSurgery`, `deleteSurgery`, and both media saves. It
schedules a snapshot through `SyncScheduler.scheduleRecordSnapshot()`, which uses
a 20-second delay with `ExistingWorkPolicy.REPLACE`. The debounce matters:
editing a patient fires a save per field, and without it every keystroke would
upload a snapshot.

Also fixed: `pushDatabaseSnapshot` now compares the export's SHA-256 against the
newest snapshot already in the drive and skips the upload when they match. The
channel had been filling with identical 242-byte snapshots, one per sync.

---

## 9. Navigation: five tabs down to three

Search and Saved were bottom-bar destinations, and neither is an area of the app.

`SearchScreen` queries bones and sections only — it never touched patients — so a
global slot advertised a library tool as a peer of the patient records. Studies
already had its own search entry point wired to `onSearchClick`, making the tab
straight duplication. Saved is bookmarks of library content plus case notes,
which is collected material from that same library.

Both are now reached from the Studies app bar and remain full screens;
`NavigationItem.showInBottomBar` decides only whether a slot is drawn. A
`BackHandler` returns to Studies from either, so a screen with no bottom-bar slot
cannot strand the user or exit the app unexpectedly.

Nothing inside `SearchScreen` or `FavoritesScreen` changed — same composables,
same ViewModels, same behaviour. Only the way in is different, which is why this
carried little risk despite touching navigation.

---

## 10. Smarter input

The autocomplete had four structural problems, all fixed in `data/suggestion/`.

**Ranking was frequency-only.** `ORDER BY useCount DESC` meant a term entered
fifty times two years ago permanently outranked one used five times last week.
`SuggestionEngine.familiarity()` now decays a use with a 90-day half-life and
takes the square root of the count, so a heavily used term stays visible without
burying everything else.

**Matching was a bare `contains`.** "fem" ranked "Periprosthetic fracture of the
femur" alongside "Femur". Matching is now tiered — whole-string prefix, word
prefix, contains, then bounded-edit-distance fuzzy — with shorter values
preferred at equal rank. A typo used to return nothing; it now finds the value,
but only once the query is four characters, below which everything is one edit
from everything.

**A blank field offered nothing.** Most of these fields have a working vocabulary
of three or four terms, so focusing an empty field now shows its top values as
chips. That removes the typing rather than speeding it up.

**Learning was indiscriminate.** Every field was split on commas and newlines,
which is right for "Penicillin, Latex" and wrong for a treatment plan — it turned
one paragraph into a dozen sentence fragments that then surfaced as suggestions.
`SuggestionCatalog` gives each field a kind: LIST splits, ATOM and PROSE are
learned whole, and prose over 160 characters is not learned at all.

**New: associations.** Schema v6 adds `field_pairs`, recording which values
appeared together in one record. `learnRecord()` captures them on save, so the
procedure field can lead with what actually followed this diagnosis last time,
marked "Usually next". This is the difference between an autocomplete and a
prediction.

Also: a fresh install is seeded with common anaesthesia types, body regions and
trauma mechanisms, scored below anything the surgeon has actually typed so they
fade as real vocabulary accumulates. Long-press a suggestion to forget it —
`forget()` existed but nothing ever called it. Rows are 48dp for gloved hands,
matches are highlighted, and `Divider` became `HorizontalDivider`.

`SuggestionEngineTest` covers the ranking rules — prefix over mid-word, recency
over raw frequency, fuzzy thresholds, prose-vs-list splitting — as pure JVM tests.

---

## 11. Patient list: one hierarchy instead of four accents

The card emphasised everything simultaneously — a filled avatar, a tinted ID
pill, the diagnosis inside a coloured box, then region and side as two more
pills, on a card carrying a border *and* elevation *and* a fill. Four competing
accent colours per row meant nothing led the eye, and the diagnosis, which is
what a surgeon scans for, was styled as secondary metadata while the record
number got a badge.

Now: name, diagnosis as plain readable text, then age, sex and region in one
muted line. The card is flat — in a long list, shadowed outlined cards read as
noise and a tint alone separates rows.

One accent survives. Laterality earns emphasis that a body region does not:
wrong-side surgery is a never-event, and "Right" reading identically to "34 yrs"
in a grey run-on line is how it gets missed. It is abbreviated to L/R so it stays
a glance rather than a read.

On the screen itself, the region filter row was the fourth stacked strip of
chrome above the first patient — app bar, tabs, search, chips. It now collapses
behind a filter button in the search row, and stays open on its own whenever a
filter is actually active, so a hidden control can never silently filter the
list.

---

## 12. Localization (English / French)

**Starting point.** `strings.xml` held exactly one entry, `app_name`. There were
zero `stringResource` call sites. Every user-facing string was a Kotlin literal
across 105 files. So this is an externalization project with a translation at the
end, not a translation task — and the infrastructure had to be right before any
of it was worth doing.

**Locale switching.** `AppLocale` stores the choice and `MainActivity.attachBaseContext`
wraps the context with it, which works on every API level and with
`ComponentActivity` — the app does not use `AppCompatActivity`, so
`AppCompatDelegate.setApplicationLocales` was not available without dragging in
appcompat and its backport service.

The setting lives in SharedPreferences rather than the app's DataStore, and that
is deliberate: `attachBaseContext` runs before the first frame and cannot
suspend, so an async read there renders the first screen in the wrong language
and then flickers. It is the one setting that genuinely needs a synchronous read.

On Android 13+ the choice is also pushed to `LocaleManager`, and
`res/xml/locales_config.xml` is declared in the manifest, so the app appears in
Settings → Apps → Ortho Tips → Language and the two can never disagree.

Changing the language calls `recreate()`. Compose caches resolved resources, so
anything less leaves half the screen in the previous language.

**The translation.** 107 strings covering Telegram Drive, the per-patient backup
card, the patient list, smart input and the language setting itself — everything
added during this work. Validated for key parity, placeholder parity (a stray
`%1$d` is a runtime crash, not a cosmetic bug) and apostrophe escaping.

One translation decision is clinical rather than linguistic: the laterality
marker on the patient card is `L`/`R` in English and **`G`/`D`** in French.
Carrying `L`/`R` across would have been a safety defect — a French surgeon reads
`D` as *droite*, and `R` means nothing in that context.

French typography is applied: narrow no-break spaces before `:` and `?`,
`Mo` rather than `MB`, and the language names in the picker are each written in
their own language, because someone hunting for French scans for "Français".

**What remains.** `tools/extract-strings.py` reports the roughly 334 literals in
34 files that are still hardcoded in user-facing positions, with generated keys
and file:line references. It reports only and never rewrites source: deciding
whether a literal is user-facing needs judgement, and `"image/jpeg"`,
`"patient.diagnosis"` and `"TdLibTelegramClient"` all look like strings while
none of them belong in `strings.xml`. Work through it a screen at a time, adding
to both `values/` and `values-fr/` together.

---

## 13. Patient details as an actionable grid

The info tab was label-and-value rows at a fixed 40/60 split. That wasted most of
the width on short facts ("Side: Right") while forcing long ones — imaging
findings, treatment plans — into a narrow column. `PatientInfoGrid` sizes tiles
instead: short facts sit two to a row, prose takes the full width, declared per
field rather than guessed from length at runtime.

**Contact fields are actionable.** A phone number a surgeon has to memorise,
leave the app for, and retype into a dialer is a phone number that gets
mis-dialled. Tapping a phone tile opens a sheet with Call, SMS, any installed
messaging app, Other apps, and Copy. Email offers Send and Copy; the address
offers Open in maps.

Long-press copies any tile, actionable or not — insurance numbers and
classifications get retyped into other systems constantly.

**Two details that decide whether this works at all.** Android 11+ hides
installed apps unless they are declared in `<queries>`; without that block every
availability check silently returns "not installed" and the sheet shows only Call
and SMS. And dialling uses `ACTION_DIAL`, not `ACTION_CALL`, so the number lands
in the dialer without this app holding the `CALL_PHONE` permission — a permission
a medical records app has no business requesting.

Named apps are WhatsApp, Telegram and Viber, each with the deep link that
actually resolves a phone number (`tg://resolve?phone=` for Telegram; the
`https://t.me/` form is an invite link, not a lookup). Everything else — Signal,
Messenger, regional messengers — is reachable through "Other apps", a chooser
over `smsto:`, so no app needs to be named here to appear.

---

## 14. Bilingual reference library

`bones.json` and `bonesFR.json` share keys and structure, so the import merges
them into one set of rows rather than two.

**Why not separate rows per language.** Giving each language its own row would
mean either a composite primary key or suffixed ids, and both break the
references that already point at bones: bookmarks, notes and imported figures. A
bookmark made in English would vanish when the app switched to French. Instead,
each table gained nullable `*Fr` columns beside the English ones (schema v7,
additive `ALTER TABLE ADD COLUMN` only), so bone ids never move.

**Fallback over blanks.** A missing or blank translation falls back to English.
The two files are maintained by hand and will drift, and a partially translated
import should be incomplete, not broken.

**Matching.** Bones match on the shared key. Sections match by index, since the
files are stated to share structure — a shorter French array leaves the tail
untranslated rather than mispairing headings with the wrong text. A missing or
malformed `bonesFR.json` is not an error: the import proceeds in English exactly
as before.

**Reading.** `ContentLocale` holds one flag, refreshed at startup and when the
language changes. Changing language recreates the activity, so from any read's
point of view the value is constant — it cannot shift between two rows of the
same list. That avoided threading a locale through fifteen `Flow` mappings and
every DAO signature for a value that cannot change mid-collection.

**Search covers both languages at once** rather than switching. A surgeon working
in French still knows the English terms, and matching both means a search never
returns empty purely because it was typed in the other language.

---

## 15. Full UI localization

Every user-facing string in the Compose layer is now a resource: **351 strings**,
each with a French translation, across 33 files and 291 call sites.

**What was excluded, deliberately.** Three categories were left as literals
because replacing them would break the build or the text:

- *Non-composable scopes.* `stringResource` is `@Composable`-only, so a literal in
  an enum constructor, data class or ViewModel cannot simply be swapped. 67 such
  literals were detected and skipped. `NavigationItem` was converted properly
  instead, taking a `@StringRes` id resolved at the call site.
- *Concatenation fragments.* 16 literals were one piece of a multi-line `"…" +
  "…"` expression; swapping a single fragment leaves a dangling `+`.
- *String templates.* Anything containing `$` was skipped, since the interpolated
  value has to become a `%1$s` placeholder and the argument passed explicitly —
  done by hand where it mattered, as in the theme row.

**Verification, all automated:** key parity between the two files, placeholder
parity (a mismatched `%1$d` is a runtime crash), no undefined `R.string` keys, no
`stringResource` outside a composable, no fused or misplaced imports, and
balanced delimiters in every file.

Translation notes: surgical register throughout (*voie d\'abord*, *ostéosynthèse*,
*compte rendu opératoire*), vouvoiement, and French typography including narrow
no-break spaces before `?` and `:` and guillemets for quoted search terms.

---

## 16. Not yet done

Honest list, so nothing here surprises you later.

- **`PatientZipImporter` writes through DAOs, not the repository.** Imported
  media therefore lands on disk without being queued directly; the next sync
  picks it up from the local scan, so it is correct but slower to reach the
  drive than a camera capture. Routing the importer through
  `repository.saveImage/saveDocument` would close the gap.
- **Foreground service for large transfers.** `TransferQueueWorker` runs as an
  ordinary worker. A 2 GB MRI series wants `setForeground` with a progress
  notification; the permissions are already in the manifest.
- **Thumbnail generation and progressive/streaming media.** TeleDrive's
  `ThumbnailStore` and `TelegramDataSource` were not ported — streaming needs
  media3, which Ortho Tips does not currently depend on.
- **Conflict resolution UI.** `ConflictPolicy.ASK` is implemented in the engine
  and reports conflicts in `SyncReport`; no screen surfaces them for a manual
  choice yet.
- **Key backup/restore.** TeleDrive's `KeyBackupCodec` lets a user carry the
  content key to a second device. Without it, an encrypted drive is readable
  only on the device that created it — the settings screen shows the key
  fingerprint so this is at least visible, but the export flow is not built.
- **Export/import of drive content to a ZIP**, and the reading-progress /
  recent-history / tags / collections categories from your brief, which do not
  exist as tables in the current schema.
