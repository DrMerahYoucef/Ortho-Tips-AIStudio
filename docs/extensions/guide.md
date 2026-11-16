# Ortho Tips — Extension guide

This guide is for an AI or a developer asked to **write a new extension** for the
Ortho Tips Android app, or to **adapt an existing HTML program** so it runs well
inside it. Read it fully before writing code. Follow the rules marked **must**.

> **How to use this file.** Give it to the AI together with your request, for example:
> *"Using guide.md, make me an Ortho Tips extension that … . Output a single .html file."*
> Then package and install it in the app: **Extensions** tab → **Gérer** → **Importer** →
> ⋮ → **Convertir un programme HTML en .OTips**. The app writes the `.OTips` file and
> offers to install it right away. Share the `.OTips` file, never the `.html`.

---

## 1. What an extension is

> **Distribution format.** Everything the app installs is an **`.OTips` package**:
> an encrypted, integrity-checked container (section 15). You write an extension
> as one `.html` file, following the rules below; the app packages it into an
> `.OTips` file. Users only ever handle `.OTips` files: they cannot be opened,
> read or edited outside Ortho Tips, and a damaged or modified file is refused.

An extension is **one self-contained `.html` file**: HTML, CSS and JavaScript in the
same file, no other files. The app lists it in the **Extensions** tab (next to
Classifications) and opens it full screen.

Where it runs:

| | |
|---|---|
| Engine | Android System WebView (Chromium, recent). Modern JS (ES2020+) and CSS are fine. |
| Screen | A phone in portrait, about **360–430 px wide**, **full screen** below the status bar. The app only draws a small round back button over the **top-left corner** (keep about 56 × 56 px there free). |
| Network | **Blocked unless the extension declares and is granted the `internet` permission** (section 4). Without it the file must work fully offline. |
| Local database | **Blocked unless the extension declares and is granted the `database` permission** (section 4). |
| Origin | Its own private origin, `https://<id>.ext.orthotips.invalid/`. |
| Access to the app | Only through `window.OrthoTips` (section 5), and only for the capabilities covered by permissions the extension was granted. It never sees other extensions. |
| Data | Saved by the app, per extension. Deleting the extension erases its data. |

The same file must still **work when opened in a normal browser**. Detect the
app with `window.OrthoTips` and fall back to plain web APIs when it is absent.
In a plain browser there is no permission system — the page gets whatever the
browser itself allows.

---

## 2. File rules

1. **Must** be a single UTF-8 `.html` file, **15 MB maximum**. Everything is inline.
2. **Must** start with `<!DOCTYPE html>`, and have a `<head>` containing
   `<meta charset="utf-8">` and
   `<meta name="viewport" content="width=device-width, initial-scale=1">`.
3. **Must** contain the metadata block of section 3 near the top of `<head>`,
   including the `ortho-ext-permissions` tag if the extension needs any (section 4).
4. **No external resources**: no CDN scripts, no Google Fonts, no remote images.
   Use system fonts, inline SVG, and `data:` URIs for images. Libraries must be
   pasted inline. This rule is about how the file is *built*, not what it may do
   at runtime — it still applies even to an extension holding the `internet`
   permission (see section 8).
5. **One page.** Build different screens as views inside the page and switch them
   in JavaScript. Do not link to other pages or reload with a different URL.
6. Inside `<script>`, never write the literal text `</script>` in a string.
   Write `<\/script>` instead.
7. The interface language is **French** unless the request says otherwise.
   `OrthoTips.language` gives the app language (`"fr"`, `"en"`) if needed.

---

## 3. Metadata block

Put these tags in `<head>`, right after the viewport tag. Always use double
quotes, and do not put `>` inside a `content` value.

```html
<meta name="ortho-ext-id" content="dems-programme">
<meta name="ortho-ext-name" content="Programme DEMS">
<meta name="ortho-ext-description" content="Programme de révision croisé avec les sujets 2015–2022.">
<meta name="ortho-ext-version" content="2.1">
<meta name="ortho-ext-icon" content="🎓">
<!-- optional -->
<meta name="ortho-ext-author" content="Dr …">
<meta name="ortho-ext-permissions" content="internet, database, storage">
```

| Name | Required | Meaning |
|---|---|---|
| `ortho-ext-id` | **yes** | Permanent identity: lowercase letters, digits and `-`, 48 characters maximum. **Never change it** in later versions. Installing an `.OTips` package whose id is already installed **replaces** the program and **keeps its data**; that is how updates work. |
| `ortho-ext-name` | **yes** | Name shown in the Extensions grid and the import screen. Short, about 30 characters. |
| `ortho-ext-description` | recommended | One sentence, shown under the name (2 lines maximum). |
| `ortho-ext-version` | recommended | Free text such as `1.0` or `2.3`. Raise it for every new version. |
| `ortho-ext-icon` | recommended | **One emoji**. The default is 🧩. |
| `ortho-ext-author` | no | Free text. |
| `ortho-ext-permissions` | no | Comma-separated list of permissions this extension needs, from the table in section 4. Omitting the tag means **no permissions** — the strictest, most compatible default. |
| `ortho-ext-network` | no — legacy | Old, single-purpose tag from before the permission system. `allow` is treated as a synonym for the `internet` permission. New extensions should use `ortho-ext-permissions` instead; keep this tag only for files written before section 4 existed. |

A file without this block still packages, using `<title>` as its name. Its id is
then derived from the title, so updates are less reliable. Always include the block.

---

## 4. Permissions

Extensions can request access to capabilities that used to be blocked outright
(internet, the local database) or that go beyond the small per-extension
key/value store (larger file storage, bundled assets, import/export). Access is
**opt-in, per capability, declared up front** — the same idea as Android app
permissions.

### 4.1 Available permissions

| Permission | Unlocks | Notes |
|---|---|---|
| `internet` | `fetch`, `XMLHttpRequest`, `WebSocket`, and loading remote images/fonts at runtime. | Does **not** relax rule 4 in section 2 — the file itself must still ship complete and inline. This is about what the *running* page may reach out to, e.g. calling a public API for reference data. |
| `database` | `OrthoTips.database.*` (section 5) — read access to Ortho Tips' local clinical reference data (bones, regions, chapters, figures — see the `clinical-bones-app` data model). | Patient records stay out of scope regardless of this permission; extensions are study/reference tools (section 8). |
| `patients` | `OrthoTips.patients.*`: search, read, **create and update** patient records and add their photos/videos (section 5). Never delete. | **Sensitive data.** The import review says so. Combined with `internet`, the extension can send records off the phone: only install extensions you trust. |
| `gemini` | `OrthoTips.gemini.*`: Gemini through **the app's key** (Réglages → Gemini), without asking the user for another key (section 5). | The key never reaches the extension; the app's model choice and fallbacks apply. |
| `storage` | `OrthoTips.files.*` (section 5) — a private on-disk folder for the extension, for data bigger than the ~2 MB key/value store is meant for. | Separate from the always-available `OrthoTips.storage` key/value API, which needs no permission. |
| `assets` | `OrthoTips.assets.read(path)` (section 5) — read-only access to extra files bundled alongside the extension. | Only meaningful for multi-file extension packages; a single `.html` file has nothing to read here. |
| `import_export` | `OrthoTips.saveFile(...)` and the `<input type="file">` flow: file picker, or the camera for `capture` inputs (section 5). | Previously always available to every extension; now explicit like everything else. |
| `sms` | `OrthoTips.sms.*`: bank movements read from SMS (debits, credits, reported balance), live and from the inbox history (section 5). | Android asks the user for SMS access the first time (`requestAccess()`). Only movement messages ever reach the extension; transfer codes and all other SMS are filtered out by the app. |
| `notifications` | `OrthoTips.notifications.*`: reminders the app shows as Android notifications, even when Ortho Tips is closed (section 5). | Android asks the user the first time (Android 13+). Reminders survive reboots and are removed with the extension. |
| `background` | `OrthoTips.media.*`: audio played by the app's media service — keeps playing with the screen off, controls in the notification panel and on the lock screen (section 5). | Import review: « Lecture audio en arrière-plan ». Streams must be `http(s)` URLs. |

Adding a capability later should mean adding one row here and one value to the
permission enum (section 14) — nothing else about this system should need to change.

### 4.2 Declaring permissions

List only what is actually used, in the metadata block (section 3):

```html
<meta name="ortho-ext-permissions" content="internet, database">
```

No tag, or an empty `content`, means the extension gets none of the capabilities
above — it behaves exactly like an extension written before this system existed.

### 4.3 Import-time review

On import, the app can show the requester what was asked for, e.g.:

> This extension requests:
> • Internet access
> • Local database access

Because every extension currently in use is written and installed by the same
person who maintains the app, this is kept **informational rather than a hard
gate**: permissions are granted automatically on import, and the summary is
there so you notice at a glance if an update suddenly asks for more than the
previous version did. If extensions are ever shared with or installed by
someone else, turn this into a blocking **Accept / Cancel** step — nothing in
the manifest format or the runtime enforcement (4.4) needs to change to do that,
only the import screen's behavior.

Whatever an extension was granted stays visible afterwards on its **Extension
Details** screen.

### 4.4 Runtime enforcement

The host must check permissions on every gated call, not just at import:

- Calling a gated API (e.g. `OrthoTips.database.query(...)`) **without** the
  matching permission must fail gracefully: reject the returned promise with a
  clear error (e.g. `PermissionError: 'database' not granted`), never throw
  synchronously or crash the WebView.
- Network access without `internet` should behave like it does today — requests
  simply fail (offline-style), not a crash.
- Denied calls should still be visible in the extension's console (**long press on the back button → Console**) so a
  missing permission is easy to diagnose while writing an extension.

### 4.5 Security & best practices

- **Least privilege by default**: no `ortho-ext-permissions` tag = no extra
  capabilities. This is also what makes every extension written before this
  section fully backward compatible.
- **Explicit, per-capability declaration** — no "allow everything" option.
- **Future-proof**: unknown values are ignored, so a manifest written for a
  newer app still installs on an older one (the missing feature is simply
  absent: feature-detect it).
- When writing a new extension, only request what it actually calls — it keeps
  the Extension Details screen an accurate, quick record of what each program
  can reach.

---

## 5. Host API — `window.OrthoTips`

The app injects this object **before any of the page's scripts run**, so it is
available synchronously at the top of your first `<script>`. In a normal
browser it is `undefined`.

```js
const HOST = window.OrthoTips || null;   // always feature-detect
```

### Properties

| Property | Type | Notes |
|---|---|---|
| `bridgeVersion` | number | `6` for this guide (`2` added the permission system, `3` notifications, `4` patients, `5` background audio and exact reminders, `6` Gemini with the app's key). |
| `extensionId` | string | The `ortho-ext-id` of this file. |
| `extensionVersion` | string | The installed version. |
| `appVersion` | string | Ortho Tips version name. |
| `language` | string | App language, such as `"fr"`. |
| `theme` | `"light"` \| `"dark"` | Current app theme. Kept up to date. |

The app also sets `data-ortho-theme="light|dark"` on `<html>`.

### Permissions

```js
OrthoTips.permissions.granted        // → array of granted permission names, e.g. ['internet','database']
OrthoTips.permissions.has('internet') // → boolean
```

Check `has()` before relying on a gated feature so the extension can show a
sensible message instead of just letting a call fail.

### Storage (synchronous, no permission needed)

```js
OrthoTips.storage.get(key)          // → string | null
OrthoTips.storage.set(key, value)   // value is converted to a string; throws if too large
OrthoTips.storage.remove(key)
OrthoTips.storage.keys(prefix)      // → sorted array of keys starting with prefix ('' = all)
```

The limits are 200 characters per key and 5 MB per value. Keep the total under
about 2 MB: store JSON, not images. For anything bigger, request `storage` and
use `OrthoTips.files` instead (below).

Programs written for other environments also keep working, because the same
store is exposed two more ways:

- **`localStorage`** is redirected to the app's store.
- **`window.storage`** matches the storage API of Claude artifacts (all methods
  return Promises):
  `get(key)` resolves to `{key, value}`, or to **`null`** if the key is missing
  (Claude rejects instead, so wrap calls in `try/catch` to support both);
  `set(key, value)` → `{key, value}`; `delete(key)` → `{key, deleted}`;
  `list(prefix)` → `{keys}`.

Do not use IndexedDB, cookies or Cache Storage. They bypass the app, so they are
not erased with the extension and not reliable.

### Network — requires `internet`

With `internet` granted, standard `fetch()` / `XMLHttpRequest` / `WebSocket`
work as they would on any web page. Without it, they fail the same way they
would offline. Rule 4 in section 2 still applies: the extension file itself
must not *depend* on the network to load — only optional, in-use features
(e.g. "fetch the latest guideline PDF") should reach for it.

### Bank SMS — requires `sms`

The app watches incoming SMS for **bank movements** (Algérie Poste format today:
« Débité / Crédité de … DA … nouveau solde … DA ») and keeps them for the
extensions holding `sms`, with a notification on arrival. Messages mentioning a
code, a password or an OTP are refused before anything is stored, and nothing
is kept while no installed extension holds `sms`.

```js
const st = await OrthoTips.sms.status();        // { available, granted, notifications }
if (!st.granted) await OrthoTips.sms.requestAccess(); // Android's SMS dialog; resolves with the new status

const past = await OrthoTips.sms.history();     // earlier movements from the inbox, oldest first
const list = await OrthoTips.sms.pending();     // movements not acknowledged yet, oldest first
// each: { id, seq?, kind: 'debit'|'credit', amount, balance|null, currency: 'DZD',
//         sentAt, receivedAt, sender, text, fingerprint }
// ...apply them, save, THEN acknowledge (a crash can repeat, never lose):
await OrthoTips.sms.ack(Math.max(...list.map(m => m.seq || 0)));

OrthoTips.sms.onMessage(() => { /* a movement arrived while open: read pending() */ });
```

- The same movement from `history()` and from `pending()` has the same
  `fingerprint`: remember the fingerprints you applied to never count twice.
- `balance` is what the bank reports after the movement; reconcile to it rather
  than trusting your own running total (messages can be missed).
- If Android shows **« Paramètre restreint »** when granting SMS access (apps
  installed from a file on recent Android): *App info → ⋮ → Allow restricted
  settings*, then grant again.
- Google Play restricts SMS permissions; this is fine for builds installed
  directly, but would need Play's approval to publish.

### Patients — requires `patients`

Read, create and update patient records and add their photos and videos,
exactly as if they were entered in the app: the patient list refreshes, and
Telegram Drive syncs (and encrypts) them like any other record. Extensions only
exchange plain values — **no encryption to handle**. They can **never delete**
a patient or a media (the user does that in the app).

```js
await OrthoTips.patients.search('karim', { limit: 20 }) // → [{ id, patientCode, fullName, dateOfBirth, diagnosis, bodyRegion, side, isArchived, updatedAt }]
                                                         //   '' → most recently updated patients; limit 1–200 (default 50)
await OrthoTips.patients.get(id)          // → the full record + media: [{ id, category, mediaType, capturedAt, notes }], or null
await OrthoTips.patients.getByCode('P-0042') // → the full record, or null (avoid duplicates when importing)
await OrthoTips.patients.create(fields)   // → { id, patientCode }   fullName required; no patientCode → next "P-0043"
await OrthoTips.patients.update(id, fields) // → { id, patientCode }  only the fields given change
await OrthoTips.patients.addMedia(id, { data, mimeType, category, capturedAt, notes }) // → { id, mediaType }
```

**Fields** (all optional except `fullName` on create; anything else is refused):

| Kind | Fields |
|---|---|
| Identity | `patientCode` (unique), `fullName`, `gender`, `dateOfBirth` |
| Contact | `phone`, `email`, `address`, `emergencyContactName`, `emergencyContactPhone` |
| Insurance | `insuranceProvider`, `insuranceNumber` |
| History | `medicalHistory`, `allergies`, `chronicDiseases`, `previousSurgeries`, `medications`, `generalNotes` |
| Orthopedics | `diagnosis`, `bodyRegion`, `side`, `injuryDate`, `traumaMechanism`, `fractureClassification`, `imagingFindings`, `treatmentPlan` |

- Text fields take strings (long texts up to 20 000 characters; names 200).
- `dateOfBirth`, `injuryDate`: epoch milliseconds, `"YYYY-MM-DD"`, or `null`.
- `side`: `LEFT`, `RIGHT`, `BILATERAL`, `NOT_APPLICABLE` (also `gauche`, `droite`, `bilatéral`).
- `id`, `createdAt`, `updatedAt`, `isArchived` are read-only.
- `addMedia`: `data` is a `Blob`, `ArrayBuffer` or `Uint8Array` (not a base64
  string); `mimeType` `image/…` (JPEG, PNG, WebP, GIF, HEIC… not SVG) or
  `video/…` (MP4, WebM, 3GP, MOV); max **30 MB**. `category`: `RADIOLOGY`,
  `CLINICAL_PHOTO`, `DOCUMENT_SCAN` or `OTHER` (default). Images are compressed,
  get a thumbnail and OCR like the app's camera; `capturedAt` defaults to the
  image's EXIF date. An image the phone cannot decode (e.g. EMF/WMF drawings
  from a PowerPoint) is refused with a clear error.
- Errors reject with a clear message (`Unknown patient field 'age'`,
  `Patient code 'P-0042' is already used`, `fullName is required`, …).

```js
// Import one patient, without creating a duplicate.
const P = OrthoTips.patients;
const existing = await P.getByCode(extracted.patientCode);
const { id } = existing
  ? await P.update(existing.id, extracted)
  : await P.create(extracted);
for (const img of images) await P.addMedia(id, { data: img.blob, mimeType: img.type, category: 'RADIOLOGY' });
```

Show the user what will be written **before** writing it (a review screen), and
say which records were created or updated afterwards.

### Gemini — requires `gemini`

Uses the Gemini key the user set **once** in the app (Réglages → Gemini), so an
extension does not have to ask for its own key. The app adds the key, picks the
model (the user's choice, newest first, with fallbacks) and returns Google's
answer unchanged. **The key never reaches the page.**

```js
await OrthoTips.gemini.status()       // → { available: true, configured, model }  configured: a key is set in the app
await OrthoTips.gemini.generate({ body, model })  // → { model, response }
                                      //   body: a generateContent request (contents, systemInstruction,
                                      //   generationConfig, tools…) — no key, no model in it
                                      //   model: optional preferred model, tried first
                                      //   response: Google's JSON (candidates, usageMetadata…)
await OrthoTips.gemini.openSettings() // opens Réglages → Gemini; the page reloads on return
```

- Errors reject with `name: 'GeminiError'` and a `kind`: `no_key` (nothing set in
  the app), `key` (refused), `quota` (limit reached: retry later), `model`,
  `server`, `network`, `blocked`, `format`; the `message` is ready to show.
- **Offer both, with the app's key as the default**: a setting « Clé de l'app
  (par défaut) / Ma propre clé ». Feature-detect: `window.OrthoTips &&
  OrthoTips.gemini` (bridge ≥ 6); on older apps, or when the user prefers it,
  use the extension's own key as before.

```js
const G = window.OrthoTips && OrthoTips.gemini;
async function askGemini(body) {
  if (settings.keyMode !== 'own' && G) {
    const st = await G.status();
    if (st.configured) return (await G.generate({ body })).response;   // same JSON as fetch()
    throw new Error('Configurez Gemini dans les Réglages de l’app, ou choisissez « Ma propre clé ».');
  }
  return callGeminiWithOwnKey(body);    // the extension's previous code
}
```

### Background audio — requires `background`

Audio played by the **app's media service** (Media3), not by the page: it keeps
playing with the screen off and when the extension is closed, and shows its
controls in the notification panel, on the lock screen and on Bluetooth
headsets. The service is the single source of truth: whoever pauses (the page,
the notification, a headset), the page is told through `onChange`.

| Call | Result |
|---|---|
| `media.play({ id, url, title, artist, artworkUrl })` | state; `url` must be `http://` or `https://` (else `Invalid stream URL`) |
| `media.pause()` / `media.resume()` / `media.stop()` | state |
| `media.setVolume(0..1)` | state |
| `media.state()` | state |
| `media.onChange(cb)` | `cb(state)` on every change, whatever made it |

State: `{ status: 'idle'|'buffering'|'playing'|'paused'|'error', item: { id, url, title, artist, artworkUrl } | null, error, volume }`.

- An extension only sees and controls **its own** item: another extension's
  stream reads as `idle`.
- `error` names the failure, with the HTTP status when the server answered one:
  `ERROR_CODE_IO_BAD_HTTP_STATUS (403)` (blocked), `… (404)` (dead stream),
  `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (no connection). The service
  follows `http` ↔ `https` redirects, so a station that moved to `https` plays
  from its old address.
- `artworkUrl` must be `https://` (otherwise no artwork).
- Calls, Bluetooth and unplugged headphones are handled by the service (pause on
  a call, pause when headphones are unplugged).
- Deleting the extension, clearing its data or an update without `background`
  stops its stream.
- Feature-detect: `window.OrthoTips && OrthoTips.media` (bridge ≥ 5). On older
  apps, fall back to an `<audio>` element (which stops with the screen off).

```js
const M = window.OrthoTips && OrthoTips.media;
if (M) {
  M.onChange(render);               // the notification or a headset can change it too
  render(await M.state());          // reopening the extension: the radio may still be playing
  playButton.onclick = () => M.play({ id: 'fip', url: 'https://…/stream.mp3', title: 'FIP', artist: 'Radio' });
}
```

### Notifications — requires `notifications`

Reminders scheduled by the extension and **shown by the app**, even when Ortho
Tips is closed or swiped away. The app keeps them across reboots, clock and
time-zone changes, and removes them when the extension is deleted, its data is
cleared, or an update no longer declares `notifications`. An extension only
sees, replaces and cancels **its own** reminders.

```js
OrthoTips.notifications.status()        // → { available: true, granted }  (granted: the app may notify)
OrthoTips.notifications.requestAccess() // → { available, granted }  Android 13+: the system dialog,
                                        //   or its settings screen once it will not ask again
OrthoTips.notifications.schedule({ id, title, body, at, repeatDays, data, exact })  // → { id, at }
OrthoTips.notifications.cancel(id)      // → true if it existed
OrthoTips.notifications.cancelAll()     // → how many were cancelled
OrthoTips.notifications.list()          // → [{ id, title, body, at, repeatDays, data }] sorted by at
OrthoTips.notifications.onOpen(cb)      // cb({ id, data }): one of its notifications tapped while open
OrthoTips.notifications.launch          // { id, data } if this opening came from a tapped notification, else null
```

- `schedule` **creates or replaces** (same `id`). `id`: 1–64 characters
  `[A-Za-z0-9._:-]`; `title`: 1–100; `body`: 0–300; `data`: optional string,
  max 1000, given back on tap. Title and body are **plain text**, never HTML.
- `at` is epoch **milliseconds**. In the past, it is shown within about a minute.
  Delivery is accurate to a few minutes (the phone's battery saver decides).
- `exact: true` (bridge v5) asks for an **exact** alarm (timers, alarms). Android
  12+ only allows it once the user enables « Alarmes et rappels » for Ortho Tips
  (off by default on Android 14+); `status().exact` says whether it is allowed.
  Without it the reminder is delivered as usual, a few minutes late at worst.
- `repeatDays`: 0 = once; N = again every N days at the **same local time**
  (09:00 stays 09:00 across daylight-saving changes; after the phone was off,
  it fires once, then continues).
- `list()` gives each reminder's **next** time in `at`, exactly as stored.
- At most **64 reminders per extension** (replacing one does not count).
  Invalid input rejects with a clear `Error` (`Too many notifications (max 64)`,
  `Invalid notification id`, …).

```js
const N = window.OrthoTips && OrthoTips.notifications;   // absent on older apps: hide the feature
async function syncReminders(plants) {
  if (!N || !(await N.status()).granted) return;
  const want = plants.map(p => ({ id: 'water:' + p.id, title: 'Arroser ' + p.name,
                                  body: p.amount || '', at: p.nextWatering, repeatDays: p.everyDays || 0,
                                  data: JSON.stringify({ plant: p.id }) }));
  const have = new Map((await N.list()).map(r => [r.id, r]));
  for (const r of want) {                        // reschedule only what changed
    const h = have.get(r.id);
    if (!h || h.at !== r.at || h.title !== r.title || h.body !== r.body || h.repeatDays !== r.repeatDays) await N.schedule(r);
    have.delete(r.id);
  }
  for (const id of have.keys()) await N.cancel(id); // plants that no longer exist
}
// Open the right screen, whether the app opened us from a notification or we were already open.
if (N && N.launch) showPlant(JSON.parse(N.launch.data).plant);
if (N) N.onOpen(ev => showPlant(JSON.parse(ev.data).plant));
// Ask only after a user gesture, e.g. an « Autoriser les notifications » button:
button.onclick = async () => { const s = await N.requestAccess(); updateUi(s.granted); };
```

### Local database — requires `database`

Implemented shape (read-only; values are always bound, never concatenated):

```js
const rows = await OrthoTips.database.query({
  table: 'bones',                 // 'chapters' | 'bones' | 'sections' | 'images'
  where: { chapterId: 'chap_1' }, // equality on listed columns (optional)
  search: 'clavicle',             // LIKE over the table's text columns (optional)
  orderBy: 'orderIndex', descending: false,
  limit: 100, offset: 0           // limit is capped at 500
});                               // → array of plain row objects
```

Columns: `chapters` (id, title, description, titleFr, descriptionFr, icon,
orderIndex) · `bones` (id, chapterId, name, subtitle, region, traumaType,
classificationSummary, nameFr, subtitleFr, regionFr, classificationSummaryFr,
orderIndex, isHighYield) · `sections` (id, boneId, sectionType, title, content,
pearls, warnings, titleFr, contentFr, pearlsFr, warningsFr, orderIndex) ·
`images` (id, boneId, sectionId, title, description, titleFr, descriptionFr,
figureNumber, tags). Patient tables and local file paths are never reachable.

```js
const rows = await OrthoTips.database.query(/* … */);
```

Read access to Ortho Tips' local clinical reference data. The exact query
shape is defined alongside the implementation (section 14) — treat this as the
contract: **Promise-based, rejects with a `PermissionError` if `database` isn't
granted**, and never exposes patient records.

### Larger file storage — requires `storage`

```js
await OrthoTips.files.write(path, data);   // data: string, Blob, ArrayBuffer or Uint8Array
const data = await OrthoTips.files.read(path);
await OrthoTips.files.list(prefix);
```

A private on-disk area for an extension, for data too big or too structured
for the key/value store above.

### Bundled assets — requires `assets`

```js
const text = await OrthoTips.assets.read('data/questions.json');
```

Read-only access to extra files shipped alongside the extension package. Not
useful for a plain single `.html` file (section 1) — relevant once an
extension ships more than one file.

### Files — import/export, requires `import_export`

```js
// Save: opens Android's "save as" screen. Resolves true if the file was written.
const ok = await OrthoTips.saveFile('backup-2026-09-19.json', jsonString, 'application/json');
// data can be a string, Blob, ArrayBuffer or Uint8Array.
```

- **Opening a file**: use a normal `<input type="file">`. The app shows Android's
  file picker (one file at a time). Read it with `await file.text()`. Do not rely
  on `accept` to filter non-media files; validate the content instead.
  `<input type="file" accept="image/*" capture="environment">` opens the camera;
  without `capture`, the file picker.
- The usual browser download trick (`<a download href="blob:…">` and `.click()`)
  also works in the app; it is routed to `saveFile`. Calling `saveFile` directly
  gives you the true/false result.
- Every extension using its own JSON backup/restore (section 6, point 5) needs
  this permission — it's the most commonly used one after the default storage.

### Theme

```js
function applyTheme(t) { document.documentElement.dataset.theme = t; }
if (HOST) { applyTheme(HOST.theme); HOST.onThemeChange(applyTheme); }
else { /* use prefers-color-scheme, see the template */ }
```

A `window` event `orthotips:theme` (with `detail` = theme) is also dispatched.
Inside the app, **follow the app's theme** and hide any theme switch the page has
of its own.

### Back button

```js
OrthoTips.onBack(() => {
  if (secondaryViewIsOpen) { showMainView(); return true; }  // handled
  return false;                                              // let the app close the extension
});
```

Return `true` only when you actually handled it, otherwise the user cannot leave.
The app's round back button (top-left corner) always closes the extension; its long press opens Reload and Console.

### Other

```js
OrthoTips.toast('Sauvegarde enregistrée');   // short Android message
OrthoTips.close();                           // close the extension
```

`alert()`, `confirm()` and `prompt()` work and show Android dialogs. Prefer
in-page UI or `toast` for non-blocking messages.

---

## 6. Behaviour every extension must have

1. **Works offline** and **in a normal browser** (section 1), unless it holds
   `internet` and is knowingly using it for an optional feature.
2. **Light and dark themes** through CSS variables on `html[data-theme="dark"]`.
   Follow `OrthoTips.theme` in the app, and `prefers-color-scheme` in a browser.
3. **Mobile first**: readable at 360 px wide, no horizontal scrolling, touch
   targets at least 44 px, `font-size` at least 13 px.
4. **Never loses data**:
   - Save automatically. Debounce writes (about 200 ms) and also save on
     `visibilitychange` → hidden.
   - Store **one JSON document** under a key prefixed with the id, such as
     `"<id>:state"`, with a schema number: `{ v: 1, … }`.
   - On load, pass stored data through a `migrate()` function. When the shape
     changes, raise the schema number and convert old data. Never discard it.
   - Wrap `JSON.parse` of stored data in `try/catch` and fall back to defaults.
5. **Backup buttons** (needs `import_export`): an export that saves
   `{ app: "<id>", v, date, data }` as `.json`, and an import that validates
   `app` and the shape before replacing anything. Deleting an extension in the
   app erases its data, so this is the user's only copy.
6. **Escapes text** before inserting it with `innerHTML`, using an `esc()` helper
   as in the template.
7. **Fast**: build the content once, avoid heavy libraries, and keep data as a
   JS constant inside the file.
8. **Keyboard-safe sheets, dialogs and fixed bars.** Inside the app the page runs
   in a WebView whose visible height changes when the keyboard opens (the app may
   resize the page, or the keyboard may cover it). A panel that works in Chrome
   can end up **stuck off-screen** in the app. Always:
   - **Never focus a field automatically** when a panel opens: no `autofocus`, no
     `input.focus()` in the opening code. Opening the keyboard mid-animation makes
     the WebView resize and scroll, and the panel stays below the screen. Let the
     user tap the field.
   - **Make the resting state the visible state.** Style the panel where it should
     end up, and animate *into* it with a CSS `@keyframes` animation. Do not hide
     it with `transform: translateY(100%)` and rely on a class added in
     `requestAnimationFrame` or a `transition` to bring it in.
   - **Size it from what is really visible**, not from `vh`: `visualViewport`
     gives the visible height and how much the keyboard covers.
   - **Bring the tapped field into view** once the keyboard is up.

   ```css
   .sheet{position:fixed;left:0;right:0;bottom:var(--kb,0px);
     max-height:calc(var(--vh,100vh) - 24px);overflow:auto;
     animation:sheet-in .25s ease both}
   @keyframes sheet-in{from{transform:translateY(100%)}to{transform:none}}
   @media (prefers-reduced-motion: reduce){.sheet{animation:none}}
   ```
   ```js
   function fitViewport(){
     const vv = window.visualViewport, root = document.documentElement.style;
     const visible = vv ? vv.height : innerHeight;
     const keyboard = vv ? Math.max(0, innerHeight - vv.height - vv.offsetTop) : 0;
     root.setProperty('--vh', visible + 'px'); root.setProperty('--kb', keyboard + 'px');
   }
   if (window.visualViewport) { visualViewport.addEventListener('resize', fitViewport);
     visualViewport.addEventListener('scroll', fitViewport); }
   addEventListener('resize', fitViewport); fitViewport();
   sheet.addEventListener('focusin', e => {             // once per sheet
     if (!e.target.matches('input,select,textarea')) return;
     setTimeout(() => { fitViewport(); e.target.scrollIntoView({block:'center'}); }, 320);
   });
   ```
   The same applies to anything `position: fixed` that holds inputs (search bars,
   dialogs, bottom toolbars).

---

## 7. Design so it feels part of Ortho Tips

- Clean Material-like cards with rounded corners (about 16 px), a subtle border
  or shadow, and 16 px page padding.
- System font stack: `system-ui, Roboto, sans-serif`. Monospace:
  `ui-monospace, "Roboto Mono", monospace`.
- The extension is **full screen**: no app bar shows its name. A compact title
  is fine when it helps, but keep the **top-left 56 × 56 px clear** for the app's
  back button (e.g. `padding-left: 56px` on the first header row).
- Keep a sticky toolbar, if any, compact: it sits at the very top of the screen,
  so leave its top-left corner to the app's back button.
- Suggested palette (adjust freely), light / dark:
  background `#f6f7f9` / `#121417`, surface `#ffffff` / `#1d2025`,
  text `#1b1d22` / `#e6e9ee`, secondary text `#5f6b76` / `#a3adb8`,
  accent `#3f51b5` / `#9fa8ff`.

---

## 8. What does not work (do not use)

| Avoid | Why / use instead |
|---|---|
| Building the file so it *needs* a CDN script, web font, or remote image just to load | Rule 4 (section 2) is unconditional — inline everything, even with `internet` granted. Reach for the network only for optional, in-use features once the page has already loaded. |
| `window.open`, `target="_blank"` for internal pages, multi-page links | One page only. External `https://` links open in the phone's browser, which is fine for references. |
| `<form>` submitting to a URL | Use `addEventListener('submit', e => e.preventDefault())` and handle it in JS. |
| Service workers, the web `Notification` API, geolocation, live camera access (`getUserMedia`), microphone, clipboard read | Not available in the extension sandbox. For notifications use `OrthoTips.notifications` (permission `notifications`, section 5). Taking a **photo** does work, through a file input: `<input type="file" accept="image/*" capture="environment">` opens the camera (section 5, needs `import_export`). |
| IndexedDB, cookies, Cache Storage | Not managed by the app. Use `OrthoTips.storage`, or `OrthoTips.files` with `storage`. |
| `<input type="file" multiple>` | One file at a time. |
| Assuming a desktop width | Design for 360 px first. |
| Deleting patients or media | Extensions can read, create and update patient records (permission `patients`, section 5) but never delete; the user does that in the app. |
| `autofocus` / `focus()` when a sheet or dialog opens | Opens the keyboard mid-animation; in the app's WebView the panel is left stuck off-screen. Let the user tap the field (section 6, point 8). |
| `vh` / `100vh` to size fixed panels | Not updated for the keyboard in a WebView. Use the visible height from `visualViewport` (section 6, point 8). |
| Panels shown only by a `transition` started in `requestAnimationFrame` | If the frame or transition does not run, the panel stays hidden. Make the visible position the resting style and animate with `@keyframes`. |
| Requesting a permission "just in case" | Ask for what the extension actually calls (section 4.5). |

---

## 9. Updating an extension

1. Keep the **same `ortho-ext-id`**.
2. Raise `ortho-ext-version`.
3. If the saved-data shape changed, raise the schema number and extend `migrate()`.
4. If the set of permissions changed, update `ortho-ext-permissions` — the
   import-time summary (section 4.3) will reflect the new list.
5. Package it again (section 1) and install the new `.OTips`. The app replaces the program, keeps the data, and shows
   "updated to version X".

---

## 10. Adapting an existing HTML program (checklist)

This is what was done to `programme-dems.html`:

1. Add the metadata block (section 3), including `ortho-ext-permissions` for
   anything the program already does that maps to section 4 (network calls,
   its own file import/export, …).
2. Remove external `<link>` and `<script src>` resources. Inline them, or
   replace fonts with system stacks (keep the original font first in the stack
   so it still shows in a browser where it is installed).
3. At the top of the script, add `const HOST = window.OrthoTips || null;`.
4. Theme: if `HOST`, hide the page's own theme switch, call
   `applyTheme(HOST.theme)`, and subscribe with `HOST.onThemeChange`.
5. Export: if `HOST`, use `await HOST.saveFile(name, json, 'application/json')`
   (needs `import_export`). Keep the blob download for browsers.
6. Back: if the program has tabs or views, register `HOST.onBack` to return to
   the main view first.
7. Any text that says "saved in this browser" should say "saved in Ortho Tips on
   this phone" when `HOST` is set.
8. Storage needs no change if it already uses `localStorage` or `window.storage`.

---

## 11. Testing before importing

1. Open the file in desktop Chrome, turn on device mode (DevTools) at **360 × 740**,
   and check light and dark (DevTools → Rendering → prefers-color-scheme).
2. Use every feature, reload the page, and confirm the data is still there.
3. Export a backup, change something, restore the backup, and confirm the change is undone.
4. Watch the DevTools console: there must be **no errors**.
5. In the app: package it as `.OTips`, install it, open it, switch the app theme,
   press Back from a secondary view, then **long-press the round back button →
   Console** to see any JavaScript errors from the phone (a red dot on the
   button means there are some). Check nothing important hides under the button.
   **Also in the app, not only in a browser:** open **every** sheet and dialog, tap
   each field so the keyboard opens, and check nothing is cut off or hidden
   under the keyboard (section 6, point 8). Chrome does not reproduce this.
6. If the extension uses a gated feature (network, database, files, assets),
   confirm it fails gracefully — no crash, a clear message or fallback — when
   the matching permission is missing, then confirm it works once granted.

---

## 12. Troubleshooting

| Symptom | Likely cause |
|---|---|
| "This file is not an HTML program" | The file has no `<html>`, `<head>`, `<body>` or `<script>` tag near the start. |
| Import created a second copy instead of updating | `ortho-ext-id` changed or is missing. |
| « Ce fichier n’est pas une extension .OTips » | You picked the `.html`; package it first (section 1). |
| « Cette extension est endommagée ou a été modifiée » | The file was altered, truncated or corrupted in transfer. Export or package it again. |
| « … créé par une ancienne version de test » | An unencrypted package from a pre-release build. Export it again from the app. |
| Blank page | A JavaScript error at load: long-press the back button → Console. Often a remote script that could not load. |
| Fonts or icons missing | They were loaded from the internet. Inline them or use system fonts / inline SVG. |
| Data gone after an update | The id changed, or `migrate()` discarded the old shape. |
| Export does nothing | The file picker was cancelled (`saveFile` resolved `false`), or `import_export` isn't in `ortho-ext-permissions`. |
| Theme does not follow the app | The code did not use `OrthoTips.theme` / `onThemeChange`, or the page hard-codes colors instead of CSS variables. |
| A gated call silently rejects | The needed permission isn't listed in `ortho-ext-permissions` — check with `OrthoTips.permissions.has(...)` and the console (long press on the back button → Console). |
| A bottom sheet or dialog opens cut off, only a field visible at the bottom (fine in Chrome) | A field is focused automatically on opening, or the panel is sized in `vh` / shown by a `requestAnimationFrame` transition. Apply section 6, point 8. |
| Import summary lists more than expected | A later version's `ortho-ext-permissions` grew — compare it against what the code actually calls. |

---

## 13. Starter template

A complete, working extension that shows every pattern above: metadata, host
detection, storage with schema and migration, debounced saves, light/dark
theme, two views with Back handling, export and import. Copy it and replace
the content. It requests no permissions — it only uses the always-available
key/value store and `saveFile`/file-input for its own backup, so it works
identically before and after the permission system.

```html
<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="ortho-ext-id" content="exemple-checklist">
<meta name="ortho-ext-name" content="Checklist exemple">
<meta name="ortho-ext-description" content="Modèle de départ : liste à cocher, sauvegarde, thème clair/sombre.">
<meta name="ortho-ext-version" content="1.0">
<meta name="ortho-ext-icon" content="✅">
<title>Checklist exemple</title>
<style>
:root{
  --bg:#f6f7f9; --surface:#ffffff; --ink:#1b1d22; --ink-2:#5f6b76;
  --line:rgba(0,0,0,.08); --accent:#3f51b5; --on-accent:#ffffff; --danger:#c62828;
  --radius:16px;
}
html[data-theme="dark"]{
  --bg:#121417; --surface:#1d2025; --ink:#e6e9ee; --ink-2:#a3adb8;
  --line:rgba(255,255,255,.08); --accent:#9fa8ff; --on-accent:#121417; --danger:#ef9a9a;
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);
  font:15px/1.5 system-ui,Roboto,sans-serif;-webkit-font-smoothing:antialiased}
main{max-width:720px;margin:0 auto;padding:16px}
h1{font-size:20px;margin:4px 0 12px}
.card{background:var(--surface);border-radius:var(--radius);padding:4px 0;border:1px solid var(--line)}
.row{display:flex;align-items:center;gap:12px;padding:12px 16px;min-height:48px}
.row + .row{border-top:1px solid var(--line)}
.row input{width:22px;height:22px;accent-color:var(--accent)}
.row.done span{color:var(--ink-2);text-decoration:line-through}
.bar{display:flex;gap:8px;flex-wrap:wrap;margin:16px 0}
button{font:inherit;border:0;border-radius:999px;padding:10px 16px;min-height:44px;
  background:var(--surface);color:var(--ink);border:1px solid var(--line)}
button.primary{background:var(--accent);color:var(--on-accent);border-color:transparent}
.muted{color:var(--ink-2);font-size:13px}
[hidden]{display:none !important}
</style>
</head>
<body>
<main>
  <section id="view-list">
    <h1>Checklist exemple</h1>
    <div class="card" id="list"></div>
    <div class="bar">
      <button class="primary" id="btn-stats">Voir le résumé</button>
      <button id="btn-export">Sauvegarder</button>
      <button id="btn-import">Restaurer</button>
      <input type="file" id="file" accept="application/json,.json" hidden>
    </div>
    <p class="muted" id="where"></p>
  </section>

  <section id="view-stats" hidden>
    <h1>Résumé</h1>
    <div class="card"><div class="row"><span id="stats"></span></div></div>
    <div class="bar"><button id="btn-back">Retour</button></div>
  </section>
</main>

<script>
/* ---- Host detection: present inside Ortho Tips, absent in a browser ---- */
const HOST = window.OrthoTips || null;
const STORE_KEY = 'exemple-checklist:state';
const SCHEMA = 1;

const ITEMS = [
  {id: 'a', label: 'Relire la classification de Garden'},
  {id: 'b', label: 'Réviser les voies d’abord de la hanche'},
  {id: 'c', label: 'Faire la fiche fracture du scaphoïde'}
];

let state = {v: SCHEMA, done: {}};

/* ---- Storage: OrthoTips.storage in the app, localStorage in a browser ---- */
const Store = {
  get(key){
    try{ return HOST ? HOST.storage.get(key) : localStorage.getItem(key); }catch(e){ return null; }
  },
  set(key, value){
    try{ HOST ? HOST.storage.set(key, value) : localStorage.setItem(key, value); }catch(e){}
  }
};

function load(){
  try{
    const raw = JSON.parse(Store.get(STORE_KEY) || 'null');
    if(raw && typeof raw === 'object') state = migrate(raw);
  }catch(e){ /* first run or unreadable: keep defaults */ }
}
function migrate(raw){
  // Bump SCHEMA and convert older shapes here; never drop the user's data.
  return {v: SCHEMA, done: (raw.done && typeof raw.done === 'object') ? raw.done : {}};
}
let saveTimer = 0;
function save(){
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => Store.set(STORE_KEY, JSON.stringify(state)), 200);
}
document.addEventListener('visibilitychange', () => {
  if(document.visibilityState === 'hidden'){ clearTimeout(saveTimer); Store.set(STORE_KEY, JSON.stringify(state)); }
});

/* ---- Theme: follow the app, else the system ---- */
function applyTheme(t){ document.documentElement.dataset.theme = t; }
if(HOST){
  applyTheme(HOST.theme);
  HOST.onThemeChange(applyTheme);
}else{
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  applyTheme(mq.matches ? 'dark' : 'light');
  mq.addEventListener('change', e => applyTheme(e.matches ? 'dark' : 'light'));
}

/* ---- Small helpers ---- */
const $ = s => document.querySelector(s);
const esc = s => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;');
function toast(msg){ if(HOST) HOST.toast(msg); else console.log(msg); }

/* ---- Views ---- */
function render(){
  $('#list').innerHTML = ITEMS.map(it => `
    <label class="row ${state.done[it.id] ? 'done' : ''}">
      <input type="checkbox" data-id="${it.id}" ${state.done[it.id] ? 'checked' : ''}>
      <span>${esc(it.label)}</span>
    </label>`).join('');
}
function showView(name){
  $('#view-list').hidden = name !== 'list';
  $('#view-stats').hidden = name !== 'stats';
  if(name === 'stats'){
    const n = ITEMS.filter(it => state.done[it.id]).length;
    $('#stats').textContent = n + ' / ' + ITEMS.length + ' faits';
  }
  window.scrollTo(0, 0);
}
// Back in the app: leave the secondary view first, then let the app close us.
if(HOST) HOST.onBack(() => {
  if(!$('#view-stats').hidden){ showView('list'); return true; }
  return false;
});

$('#list').addEventListener('change', e => {
  const id = e.target.dataset.id;
  if(!id) return;
  state.done[id] = e.target.checked;
  save(); render();
});
$('#btn-stats').addEventListener('click', () => showView('stats'));
$('#btn-back').addEventListener('click', () => showView('list'));

/* ---- Backup: export and restore a JSON file ---- */
$('#btn-export').addEventListener('click', async () => {
  const name = 'exemple-checklist-' + new Date().toISOString().slice(0, 10) + '.json';
  const json = JSON.stringify({app: 'exemple-checklist', v: SCHEMA, date: new Date().toISOString(), data: state}, null, 2);
  if(HOST){
    if(await HOST.saveFile(name, json, 'application/json')) toast('Sauvegarde enregistrée');
    return;
  }
  const url = URL.createObjectURL(new Blob([json], {type: 'application/json'}));
  const a = Object.assign(document.createElement('a'), {href: url, download: name});
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
});
$('#btn-import').addEventListener('click', () => $('#file').click());
$('#file').addEventListener('change', async e => {
  const file = e.target.files && e.target.files[0];
  e.target.value = '';
  if(!file) return;
  try{
    const raw = JSON.parse(await file.text());
    if(!raw || raw.app !== 'exemple-checklist' || !raw.data) throw new Error('format');
    state = migrate(raw.data);
    save(); render();
    toast('Sauvegarde restaurée');
  }catch(err){
    alert('Ce fichier n’est pas une sauvegarde de cette extension.');
  }
});

/* ---- Start ---- */
$('#where').textContent = HOST
  ? 'Enregistré dans Ortho Tips, sur ce téléphone.'
  : 'Enregistré dans ce navigateur.';
load();
render();
</script>
</body>
</html>
```

---

## 14. For developers: where the host lives in the app

| File | Role |
|---|---|
| `data/extensions/otips/OTipsFormat.kt` | Container layout, errors, limits, signature hook. |
| `data/extensions/otips/OTipsCrypto.kt` | Header and chunked payload encryption (AES-256-GCM, HKDF-SHA256), key ring. |
| `data/extensions/otips/OTipsReader.kt` / `OTipsWriter.kt` | Streaming verification + extraction; deterministic, encrypted writing. Also compiled into `buildSrc`. |
| `data/extensions/ExtensionManifest.kt` | Manifest model and validation; version ordering. |
| `data/extensions/ExtensionPermission.kt` | The permissions of section 4.1 — single source of truth. |
| `data/extensions/ExtensionKind.kt` + `ExtensionHandler.kt` | Kinds (`html`, `operative-guide`, `classifications`) and the handler interface: adding a kind = one handler registered in `di/AppModule.kt`. |
| `data/extensions/ExtensionRepository.kt` | Inspect, install (atomic, verified), update, delete, export, HTML → `.OTips`, legacy migration. Layout: `filesDir/extensions/<id>/{install.json,content/,files/}`. |
| `data/extensions/*ExtensionHandler*`, `OperativeGuideExtension.kt`, `ClassificationsExtension.kt` | Kind-specific validation and activation. |
| `OperativeGuideMigration` (in `OperativeGuideExtension.kt`) | For updates only: guide data already on the phone gets its extension installed silently. Nothing is ever installed automatically on a new installation; bundled extensions are installed by the user from the import screen. |
| `ui/screens/extensions/ExtensionBridge.kt` + `assets/extension_host/ortho-bridge.js` | `window.OrthoTips` (bridge v6); permissions enforced on every call. |
| `data/ai/GeminiService.kt` (`generateRaw`) + `ExtensionBridge.kt` (`gemini.*`) | `OrthoTips.gemini`: extensions use the app's Gemini key through the app; the key never reaches the page. |
| `data/extensions/media/ExtensionMediaService.kt` + `ui/screens/extensions/ExtensionMedia.kt` | `OrthoTips.media`: the Media3 service (screen off, notification, lock screen, headsets) and the per-extension controller that pushes its state to the page. |
| `data/extensions/ExtensionPatients.kt` + `ExtensionPatientRules.kt` | `OrthoTips.patients`: field whitelist and validation, records and media written through `PatientRepository` (never deleted). |
| `data/reminders/*` + `ui/screens/extensions/ExtensionNotificationRouter.kt` | `OrthoTips.notifications`: rules and storage (`ReminderRules`, `ExtensionReminderStore`), alarms and notifications (`ExtensionReminders`), boot/alarm receivers, taps routed to `onOpen` / `launch`. |
| `ui/screens/extensions/ExtensionImportScreen.kt` | Bundled / updates / external / installed, and the import-time review (4.3). |
| `ui/screens/extensions/ExtensionsTab.kt` | Manager: open, details (granted permissions), export, clear data, delete. |
| `app/src/extensions/<id>/` + `buildSrc` (`packBundledExtensions`) | Sources of bundled extensions, packed into encrypted `.OTips` assets at build time. |
| `data/classification/*`, `ui/screens/classifications/ClassificationEditorScreens.kt` | The Word-style classification editor; publishes classification packs as `.OTips`. |

## 15. The `.OTips` format and its protection

- **Container v2**: magic, version, flags, an **encrypted header block**
  (manifest, sizes, hashes), the **encrypted payload** (a ZIP of the content),
  a reserved signature block, and a SHA-256 trailer over the whole file.
- **Encryption**: every package has its own keys, derived with HKDF-SHA256 from
  the app key and a random salt. The header is sealed with AES-256-GCM; the
  payload with AES-256-GCM in 64 KB chunks, each bound to the header, its index
  and an end marker, so nothing can be altered, reordered, truncated or swapped
  between files. Large packages stream without being held in memory.
- **Validation** before anything is installed: trailer checksum, header and every
  chunk authenticated, plain-content SHA-256, manifest rules (id, kind, safe
  paths), size limits (zip-bomb safe), and the kind's own checks (e.g. every
  classification figure exists). Installation is atomic: a refused or failed
  package leaves the previous version untouched.
- **Versioning**: container version, manifest `version` (ordered numerically),
  `minAppVersionCode`, per-kind `contentSchema`, and a key id for key rotation.
  Unencrypted version-1 files from pre-release builds are refused as outdated.
- **Signatures (prepared)**: the `SIGNED` flag and signature block are part of
  the format and `OTipsSignatureVerifier` is called during verification; no
  publisher key is enforced yet.
- **Protection level, honestly**: files cannot be read or edited with ordinary
  tools, and any modification is detected. The app key ships inside the app, as
  it must for the app to decrypt offline; a determined reverse engineer with the
  APK could in principle extract it. Publisher signatures (above) are the step
  that closes that gap for official packages.

## 16. Built-in kinds

- **Operative guide** (`operative-guide`): the package enables the guide; its
  content is the user's own `bones_database.zip`, imported afterwards from the
  guide's tab and never bundled. Deleting the extension deletes that data
  (bookmarks and notes are kept).
- **Classifications** (`classifications`): packs of chapters (JSON) and figures.
  Several packs can be installed; they are shown together and in the patient
  picker. Create or edit them with the **classification editor** (Classifications
  tab → ✎): paste text, select lines, apply Title / Subtitle / Sub-subtitle /
  headings, text, notes, bullet / numbered / lettered lists nested at any depth,
  images and tables; **Publish** installs the pack, **Export** shares its `.OTips`.
