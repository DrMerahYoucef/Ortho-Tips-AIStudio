/*
 * Ortho Tips — extension host bridge (API version 6).
 *
 * Version 6 adds `OrthoTips.gemini` (permission `gemini`): Gemini through the
 * app's own key (Réglages → Gemini). The key never reaches the page.
 *
 * Version 5 implements the reserved `background` permission:
 * `OrthoTips.media` plays an audio stream in the app's Media3 service, so it
 * keeps playing with the screen off and shows its controls in the notification
 * panel and on the lock screen. The service is the single source of truth: its
 * state is pushed back to the page (`media.onChange`) whatever changed it
 * (the page, the notification, a Bluetooth headset). Notifications also accept
 * `exact: true` (an exact alarm when Android allows it).
 *
 * Injected as the first script of every extension page, before any of the
 * page's own code. It turns the raw Android interface `OrthoTipsNative` into:
 *
 *   window.OrthoTips   the documented host API (see guide.md)
 *   window.storage     Claude-artifact compatible async key/value storage
 *   localStorage       redirected to the same app-managed store
 *   <a download>       blob:/data: downloads saved through Android's file picker
 *
 * Version 2 adds the permission system (extension guide, section 4):
 * `OrthoTips.permissions`, and the gated `files`, `assets` and `database` APIs.
 * A gated call without its permission rejects with a PermissionError and never
 * throws synchronously; the app enforces the same rule natively.
 *
 * Everything an extension saves lives in the app, per extension, and is erased
 * when the extension is deleted or its data is cleared from the Extensions tab.
 */
(function () {
  'use strict';
  if (window.OrthoTips) return;
  var N = window.OrthoTipsNative;
  if (!N) return;

  var info = {};
  try { info = JSON.parse(N.info()) || {}; } catch (e) { info = {}; }

  // ---- async requests answered by the app ----------------------------------
  var pending = {};
  var seq = 0;
  function request(start) {
    return new Promise(function (resolve) {
      var id = 'q' + (++seq);
      pending[id] = resolve;
      try { start(id); } catch (e) { delete pending[id]; resolve(false); }
    });
  }
  function resolveRequest(id, value) {
    var done = pending[id];
    if (!done) return;
    delete pending[id];
    done(value);
  }

  // ---- permissions ---------------------------------------------------------
  var granted = Array.isArray(info.permissions) ? info.permissions.map(String) : [];
  function has(name) { return granted.indexOf(String(name)) !== -1; }

  function PermissionError(permission) {
    var e = new Error("PermissionError: '" + permission + "' not granted");
    e.name = 'PermissionError';
    e.permission = permission;
    return e;
  }
  function denied(permission) {
    var e = PermissionError(permission);
    try { console.warn(e.message); } catch (x) {}
    return Promise.reject(e);
  }

  // ---- promise-based native calls (files, assets, database) ------------------
  var calls = {};
  var callSeq = 0;
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = 'c' + (++callSeq);
      calls[id] = { resolve: resolve, reject: reject };
      try { N.call(id, method, JSON.stringify(args || {})); }
      catch (e) { delete calls[id]; reject(e); }
    });
  }
  function settleCall(id, ok, value) {
    var c = calls[id];
    if (!c) return;
    delete calls[id];
    if (ok) { c.resolve(value); return; }
    var message = String(value);
    var e = new Error(message);
    if (message.indexOf('PermissionError') === 0) e.name = 'PermissionError';
    // "GeminiError(quota): …" → Error { name: 'GeminiError', kind: 'quota', message: '…' }
    var g = /^GeminiError\((\w+)\): ([\s\S]*)$/.exec(message);
    if (g) { e = new Error(g[2]); e.name = 'GeminiError'; e.kind = g[1]; }
    c.reject(e);
  }
  function gated(permission, method, args) {
    return has(permission) ? call(method, args) : denied(permission);
  }
  function fromBase64(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }
  /** Decodes base64 from the app as text (default), 'base64' or 'arraybuffer'. */
  function decode(b64, as) {
    if (b64 === null || b64 === undefined) return null;
    if (as === 'base64') return b64;
    var bytes = fromBase64(b64);
    if (as === 'arraybuffer') return bytes.buffer;
    return new TextDecoder('utf-8').decode(bytes);
  }

  // ---- storage -------------------------------------------------------------
  var store = {
    get: function (key) {
      var v = N.storageGet(String(key));
      return v === null || v === undefined ? null : String(v);
    },
    set: function (key, value) {
      if (!N.storageSet(String(key), String(value))) {
        throw new Error('Ortho Tips storage: value too large or storage full');
      }
    },
    remove: function (key) { N.storageDelete(String(key)); },
    keys: function (prefix) {
      try { return JSON.parse(N.storageKeys(prefix == null ? '' : String(prefix))); }
      catch (e) { return []; }
    }
  };

  // Same shape as the storage API of Claude artifacts, so programs made there
  // keep their data here without changes. `get` resolves to null for a missing
  // key (Claude rejects instead; code written for both uses try/catch).
  if (!window.storage) {
    window.storage = {
      get: function (key, shared) {
        return Promise.resolve().then(function () {
          var v = store.get(key);
          return v === null ? null : { key: key, value: v, shared: !!shared };
        });
      },
      set: function (key, value, shared) {
        return Promise.resolve().then(function () {
          store.set(key, value);
          return { key: key, value: String(value), shared: !!shared };
        });
      },
      delete: function (key, shared) {
        return Promise.resolve().then(function () {
          store.remove(key);
          return { key: key, deleted: true, shared: !!shared };
        });
      },
      list: function (prefix, shared) {
        return Promise.resolve().then(function () {
          return { keys: store.keys(prefix), prefix: prefix, shared: !!shared };
        });
      }
    };
  }

  // localStorage, backed by the same store. If the WebView refuses the swap,
  // the native localStorage stays in place and still persists.
  try {
    var ls = {
      getItem: function (k) { return store.get(k); },
      setItem: function (k, v) { store.set(k, v); },
      removeItem: function (k) { store.remove(k); },
      clear: function () { store.keys('').forEach(function (k) { store.remove(k); }); },
      key: function (i) { var ks = store.keys(''); return i >= 0 && i < ks.length ? ks[i] : null; }
    };
    Object.defineProperty(ls, 'length', {
      configurable: true, get: function () { return store.keys('').length; }
    });
    var proxy = new Proxy(ls, {
      get: function (t, p) {
        if (p in t) return t[p];
        if (typeof p !== 'string') return undefined;
        var v = store.get(p);
        return v === null ? undefined : v;
      },
      set: function (t, p, v) {
        if (typeof p === 'string' && !(p in t)) store.set(p, v);
        return true;
      },
      deleteProperty: function (t, p) {
        if (typeof p === 'string') store.remove(p);
        return true;
      },
      has: function (t, p) { return (p in t) || (typeof p === 'string' && store.get(p) !== null); },
      ownKeys: function () { return store.keys(''); },
      getOwnPropertyDescriptor: function (t, p) {
        var v = typeof p === 'string' ? store.get(p) : null;
        return v === null ? undefined : { value: v, enumerable: true, configurable: true, writable: true };
      }
    });
    Object.defineProperty(window, 'localStorage', {
      configurable: true, enumerable: true, get: function () { return proxy; }
    });
  } catch (e) { /* keep the native localStorage */ }

  // ---- files -----------------------------------------------------------------
  function toBytes(data) {
    if (typeof Blob !== 'undefined' && data instanceof Blob) {
      return data.arrayBuffer().then(function (b) { return new Uint8Array(b); });
    }
    if (data instanceof ArrayBuffer) return Promise.resolve(new Uint8Array(data));
    if (ArrayBuffer.isView(data)) {
      return Promise.resolve(new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
    }
    return Promise.resolve(new TextEncoder().encode(data == null ? '' : String(data)));
  }
  function toBase64(bytes) {
    var out = '';
    var CHUNK = 0x8000;
    for (var i = 0; i < bytes.length; i += CHUNK) {
      out += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
    }
    return btoa(out);
  }
  function guessMime(name) {
    var ext = String(name || '').split('.').pop().toLowerCase();
    return {
      json: 'application/json', txt: 'text/plain', csv: 'text/csv', html: 'text/html',
      md: 'text/markdown', pdf: 'application/pdf', png: 'image/png', jpg: 'image/jpeg',
      jpeg: 'image/jpeg', svg: 'image/svg+xml'
    }[ext] || 'application/octet-stream';
  }
  /** Asks where to save, then writes. Resolves to true when the file was written. */
  function saveFile(fileName, data, mimeType) {
    if (!has('import_export')) {
      try { console.warn(PermissionError('import_export').message); } catch (x) {}
      return Promise.resolve(false);
    }
    var name = String(fileName || 'fichier');
    var mime = mimeType || (data && data.type) || guessMime(name);
    return toBytes(data).then(function (bytes) {
      var b64 = toBase64(bytes);
      return request(function (id) { N.saveFile(id, name, mime, b64); });
    }).catch(function () { return false; });
  }
  function saveFromUrl(url, name) {
    return fetch(url)
      .then(function (r) { return r.blob(); })
      .then(function (blob) { return saveFile(name, blob, blob.type || guessMime(name)); })
      .catch(function () { return false; });
  }
  function isLocalDownload(a) {
    return a && a.hasAttribute && a.hasAttribute('download') && /^(blob:|data:)/i.test(a.href || '');
  }
  // Android's WebView cannot follow <a download> to a blob: or data: URL, which
  // is how browser programs usually export a file. Route those through saveFile.
  var nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (isLocalDownload(this)) {
      saveFromUrl(this.href, this.getAttribute('download') || 'fichier');
      return;
    }
    return nativeClick.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (isLocalDownload(a)) {
      e.preventDefault();
      saveFromUrl(a.href, a.getAttribute('download') || 'fichier');
    }
  }, true);

  // ---- theme, back, misc -----------------------------------------------------
  var themeListeners = [];
  var notificationListeners = [];
  var mediaListeners = [];
  // Set when the app opened this extension because a notification was tapped.
  var launch = null;
  if (info.launch && info.launch.source === 'notification' && has('notifications')) {
    launch = { id: String(info.launch.id || ''), data: info.launch.data == null ? null : String(info.launch.data) };
  }
  var backHandlers = [];

  function markTheme(t) {
    try { document.documentElement.setAttribute('data-ortho-theme', t); } catch (e) {}
  }

  var api = {
    bridgeVersion: 6,
    extensionId: String(info.extensionId || ''),
    extensionVersion: String(info.extensionVersion || ''),
    appVersion: String(info.appVersion || ''),
    language: String(info.language || 'fr'),
    theme: info.theme === 'dark' ? 'dark' : 'light',

    storage: { get: store.get, set: store.set, remove: store.remove, keys: store.keys },

    permissions: {
      granted: granted.slice(),
      has: has
    },
    // Requires `storage`: a private folder for data too big for `storage`.
    files: {
      write: function (path, data) {
        if (!has('storage')) return denied('storage');
        return toBytes(data).then(function (bytes) {
          return call('files.write', { path: String(path), base64: toBase64(bytes) });
        });
      },
      read: function (path, options) {
        var as = options && options.as;
        return gated('storage', 'files.read', { path: String(path) })
          .then(function (b64) { return decode(b64, as); });
      },
      list: function (prefix) {
        return gated('storage', 'files.list', { prefix: prefix == null ? '' : String(prefix) });
      },
      delete: function (path) { return gated('storage', 'files.delete', { path: String(path) }); }
    },
    // Requires `assets`: read-only access to other files shipped in the package.
    assets: {
      read: function (path, options) {
        var as = options && options.as;
        return gated('assets', 'assets.read', { path: String(path) })
          .then(function (b64) { return decode(b64, as); });
      }
    },
    // Requires `sms`: bank movements read from SMS (debits, credits, balances).
    // Codes and all other messages are filtered out by the app.
    sms: {
      status: function () { return gated('sms', 'sms.status', {}); },
      requestAccess: function () { return gated('sms', 'sms.requestAccess', {}); },
      pending: function () { return gated('sms', 'sms.pending', {}); },
      ack: function (upTo) { return gated('sms', 'sms.ack', { upTo: Number(upTo) || 0 }); },
      history: function () { return gated('sms', 'sms.history', {}); },
      onMessage: function (cb) {
        if (typeof cb !== 'function' || !has('sms')) return;
        window.addEventListener('orthotips:sms', function () {
          try { cb(); } catch (e) { try { console.error(e); } catch (x) {} }
        });
      }
    },
    // Requires `patients`: read, create and update patient records and add their
    // media (never delete). The app stores them as if entered by hand.
    patients: {
      search: function (query, options) {
        var limit = Math.floor(Number((options || {}).limit) || 50);
        return gated('patients', 'patients.search', { query: query == null ? '' : String(query), limit: Math.max(1, Math.min(200, limit)) });
      },
      get: function (id) { return gated('patients', 'patients.get', { id: String(id) }); },
      getByCode: function (code) { return gated('patients', 'patients.getByCode', { code: String(code) }); },
      create: function (fields) { return gated('patients', 'patients.create', { fields: fields || {} }); },
      update: function (id, fields) { return gated('patients', 'patients.update', { id: String(id), fields: fields || {} }); },
      addMedia: function (patientId, media) {
        if (!has('patients')) return denied('patients');
        media = media || {};
        if (media.data == null) return Promise.reject(new Error('Missing media data'));
        return toBytes(media.data).then(function (bytes) {
          return call('patients.addMedia', {
            patientId: String(patientId),
            base64: toBase64(bytes),
            mimeType: String(media.mimeType || (media.data && media.data.type) || ''),
            category: media.category == null ? null : String(media.category),
            capturedAt: media.capturedAt == null ? null : Number(media.capturedAt),
            notes: media.notes == null ? '' : String(media.notes)
          });
        });
      }
    },
    // Requires `notifications`: reminders scheduled and shown by the app, even
    // when Ortho Tips is closed. The app keeps them across reboots and cancels
    // them when the extension is deleted or loses the permission.
    notifications: {
      launch: launch,
      status: function () { return gated('notifications', 'notifications.status', {}); },
      requestAccess: function () { return gated('notifications', 'notifications.requestAccess', {}); },
      schedule: function (n) {
        n = n || {};
        return gated('notifications', 'notifications.schedule', {
          id: String(n.id == null ? '' : n.id),
          title: String(n.title == null ? '' : n.title),
          body: n.body == null ? '' : String(n.body),
          at: Number(n.at),
          repeatDays: Math.max(0, Math.floor(Number(n.repeatDays) || 0)),
          data: n.data == null ? null : String(n.data),
          exact: !!n.exact
        });
      },
      cancel: function (id) { return gated('notifications', 'notifications.cancel', { id: String(id) }); },
      cancelAll: function () { return gated('notifications', 'notifications.cancelAll', {}); },
      list: function () { return gated('notifications', 'notifications.list', {}); },
      onOpen: function (cb) {
        if (typeof cb === 'function' && has('notifications')) notificationListeners.push(cb);
      }
    },
    // Requires `gemini`: Gemini through the app's key (Réglages → Gemini). The key
    // never reaches the page; the app picks the model (newest, with fallbacks).
    gemini: {
      // → { available: true, configured, model }  (configured: a key is set in the app)
      status: function () { return gated('gemini', 'gemini.status', {}); },
      // request.body: a generateContent body (contents, systemInstruction, generationConfig…),
      // request.model: optional preferred model. → { model, response } (Google's JSON answer)
      generate: function (request) {
        request = request || {};
        return gated('gemini', 'gemini.generate', { body: request.body || {}, model: request.model == null ? '' : String(request.model) });
      },
      // Opens the app's Réglages → Gemini (the page is reloaded when the user comes back).
      openSettings: function () { return gated('gemini', 'gemini.openSettings', {}); }
    },
    // Requires `background`: audio played by the app's media service — keeps
    // playing with the screen off, with controls in the notification panel.
    media: {
      play: function (item) {
        item = item || {};
        return gated('background', 'media.play', {
          id: String(item.id == null ? '' : item.id), url: String(item.url || ''),
          title: String(item.title || ''), artist: item.artist == null ? '' : String(item.artist),
          artworkUrl: item.artworkUrl == null ? '' : String(item.artworkUrl)
        });
      },
      pause: function () { return gated('background', 'media.pause', {}); },
      resume: function () { return gated('background', 'media.resume', {}); },
      stop: function () { return gated('background', 'media.stop', {}); },
      setVolume: function (v) { return gated('background', 'media.setVolume', { volume: Math.max(0, Math.min(1, Number(v) || 0)) }); },
      // → { status: 'idle'|'buffering'|'playing'|'paused'|'error', item|null, error, volume }
      state: function () { return gated('background', 'media.state', {}); },
      onChange: function (cb) { if (typeof cb === 'function' && has('background')) mediaListeners.push(cb); }
    },
    // Requires `database`: read-only clinical reference data, never patients.
    database: {
      query: function (spec) { return gated('database', 'database.query', spec || {}); }
    },

    saveFile: saveFile,
    toast: function (message) { N.toast(String(message)); },
    close: function () { N.close(); },
    onThemeChange: function (cb) { if (typeof cb === 'function') themeListeners.push(cb); },
    onBack: function (cb) { if (typeof cb === 'function') backHandlers.push(cb); },

    // Called by the app, not by extensions.
    __resolve: resolveRequest,
    __settle: settleCall,
    __setTheme: function (t) {
      var theme = t === 'dark' ? 'dark' : 'light';
      if (theme === api.theme) return;
      api.theme = theme;
      markTheme(theme);
      themeListeners.forEach(function (cb) { try { cb(theme); } catch (e) {} });
      try { window.dispatchEvent(new CustomEvent('orthotips:theme', { detail: theme })); } catch (e) {}
    },
    // A notification of this extension was tapped while it is already open.
    __notificationOpened: function (id, data) {
      var ev = { id: String(id || ''), data: data == null ? null : String(data) };
      notificationListeners.forEach(function (cb) { try { cb(ev); } catch (e) { try { console.error(e); } catch (x) {} } });
      try { window.dispatchEvent(new CustomEvent('orthotips:notification', { detail: ev })); } catch (e) {}
    },
    // The media service's state changed (any controller: page, notification, headset).
    __mediaChanged: function (state) {
      var st = state;
      if (typeof st === 'string') { try { st = JSON.parse(st); } catch (e) { return; } }
      mediaListeners.forEach(function (cb) { try { cb(st); } catch (e) { try { console.error(e); } catch (x) {} } });
      try { window.dispatchEvent(new CustomEvent('orthotips:media', { detail: st })); } catch (e) {}
    },
    __handleBack: function () {
      for (var i = backHandlers.length - 1; i >= 0; i--) {
        try { if (backHandlers[i]() === true) return true; } catch (e) {}
      }
      return false;
    }
  };

  markTheme(api.theme);
  window.OrthoTips = api;
})();
