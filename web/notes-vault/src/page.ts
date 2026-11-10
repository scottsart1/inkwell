import { renderMarkdown } from "./markdown";

/**
 * The vault's single web page. Everything is inline (CSS and JS carry the per-response
 * CSP nonce); the only external resource is the Figtree stylesheet from Google Fonts.
 * The link id is never embedded: the script derives its API base from location.pathname.
 */

const LOCK_ICON =
  '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">' +
  '<path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" ' +
  'd="M7.5 10.5V7.5a4.5 4.5 0 0 1 9 0v3M6.5 10.5h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1zM12 14.5v2"/></svg>';

const CSS = String.raw`
:root {
  --ground: #F5F4F0; --surface: #FFFFFF; --surface-alt: #EDEBE5; --ink: #1A1916; --muted: #57544C;
  --accent: #2952CC; --accent-container: #E3E9FB; --on-accent: #FFFFFF; --outline: #DDD9CF; --danger: #B3261E;
  --r-s: 8px; --r-m: 12px; --r-l: 16px;
  --font: Figtree, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
  color-scheme: light;
}
@media (prefers-color-scheme: dark) {
  :root {
    --ground: #141412; --surface: #1D1C1A; --surface-alt: #262522; --ink: #EDEBE5; --muted: #A9A59B;
    --accent: #8FA8F0; --accent-container: #22305A; --on-accent: #0E1A3D; --outline: #3A3833; --danger: #F2B8B5;
    color-scheme: dark;
  }
}
*, *::before, *::after { box-sizing: border-box; }
html, body { margin: 0; }
body { background: var(--ground); color: var(--ink); font: 15px/1.5 var(--font); -webkit-font-smoothing: antialiased; text-rendering: optimizeLegibility; }
[hidden] { display: none !important; }
.visually-hidden { position: absolute !important; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
button, input { font: inherit; color: inherit; }
.btn { min-height: 44px; padding: 0 18px; border-radius: var(--r-s); border: 1px solid var(--outline); background: var(--surface); color: var(--ink); font-weight: 600; cursor: pointer; }
.btn:hover { background: var(--surface-alt); }
.btn.primary { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
.btn.primary:hover { background: var(--accent); opacity: .92; }
.btn:disabled { opacity: .6; cursor: progress; }
.input { width: 100%; min-height: 44px; padding: 0 12px; border-radius: var(--r-s); border: 1px solid var(--outline); background: var(--surface); color: var(--ink); }
.input::placeholder { color: var(--muted); opacity: 1; }
.input:focus-visible { outline: 2px solid var(--accent); outline-offset: 0; border-color: var(--accent); }
.muted { color: var(--muted); }

/* Locked */
.login { min-height: 100vh; min-height: 100dvh; display: grid; place-items: center; padding: 24px 16px; }
.card { width: 100%; max-width: 380px; background: var(--surface); border: 1px solid var(--outline); border-radius: var(--r-l); padding: 32px 28px 28px; }
.card h1 { margin: 0 0 24px; font-size: 26px; line-height: 32px; font-weight: 600; letter-spacing: -0.3px; display: flex; align-items: center; gap: 10px; }
.card h1 svg { color: var(--accent); }
.label { display: block; margin: 0 0 6px; font-size: 14px; font-weight: 500; }
.card .btn { width: 100%; margin-top: 16px; }
.error { margin: 10px 0 0; color: var(--danger); font-size: 14px; }
.hint { margin: 20px 0 0; color: var(--muted); font-size: 13px; text-align: center; }

/* Unlocked */
.app { display: flex; flex-direction: column; height: 100vh; height: 100dvh; }
.topbar { display: flex; align-items: center; gap: 16px; padding: 10px 24px; background: var(--surface); border-bottom: 1px solid var(--outline); }
.brand { display: flex; align-items: center; gap: 10px; margin: 0; font-size: 17px; line-height: 24px; font-weight: 600; white-space: nowrap; }
.brand svg { color: var(--accent); flex: none; }
.search { flex: 1; max-width: 520px; margin-left: auto; }
.layout { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(260px, 340px) minmax(0, 1fr); }
.list-pane { min-height: 0; overflow: auto; padding: 16px 12px; border-right: 1px solid var(--outline); }
.list-status { margin: 0 4px 10px; color: var(--muted); font-size: 13px; }
.doc-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; }
.doc-item { display: grid; gap: 2px; width: 100%; min-height: 44px; padding: 10px 12px; text-align: left; background: transparent; border: 1px solid transparent; border-radius: var(--r-m); cursor: pointer; }
.doc-item:hover { background: var(--surface); border-color: var(--outline); }
.doc-item[aria-current="true"] { background: var(--accent-container); border-color: var(--accent-container); }
.doc-title { font-weight: 600; overflow-wrap: anywhere; }
.doc-meta, .doc-counts { color: var(--muted); font-size: 13px; line-height: 18px; }
.detail { min-height: 0; overflow: auto; padding: 28px 32px 64px; }
.detail:focus { outline: none; }
.detail:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.empty { margin: 48px auto; max-width: 360px; text-align: center; color: var(--muted); }
.doc { max-width: 880px; }
.doc-heading { margin: 0 0 6px; font-size: 26px; line-height: 32px; font-weight: 600; letter-spacing: -0.3px; overflow-wrap: anywhere; }
.meta { margin: 0; color: var(--muted); font-size: 13px; line-height: 18px; }
.tags { list-style: none; display: flex; flex-wrap: wrap; gap: 6px; margin: 12px 0 0; padding: 0; }
.tag { padding: 2px 10px; border-radius: var(--r-s); background: var(--surface-alt); font-size: 13px; }
.doc-section { margin-top: 28px; }
.section-title { margin: 0 0 10px; color: var(--muted); font-size: 12px; line-height: 16px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; }
.panel { background: var(--surface); border: 1px solid var(--outline); border-radius: var(--r-l); padding: 20px 24px; }
.panel + .panel { margin-top: 12px; }

/* Rendered Markdown */
.markdown { overflow-wrap: anywhere; }
.markdown > :first-child { margin-top: 0; }
.markdown > :last-child { margin-bottom: 0; }
.markdown p, .markdown ul, .markdown ol, .markdown pre, .markdown blockquote, .markdown .table-wrap { margin: 0 0 12px; }
.markdown h3, .markdown h4, .markdown h5, .markdown h6 { margin: 20px 0 8px; font-weight: 600; letter-spacing: -0.2px; }
.markdown .md-h1 { font-size: 22px; line-height: 28px; }
.markdown .md-h2 { font-size: 18px; line-height: 24px; }
.markdown .md-h3 { font-size: 16px; line-height: 22px; }
.markdown .md-h4, .markdown .md-h5, .markdown .md-h6 { font-size: 15px; line-height: 20px; }
.markdown ul, .markdown ol { padding-left: 24px; }
.markdown li + li { margin-top: 2px; }
.markdown li > ul, .markdown li > ol { margin: 4px 0 0; }
.markdown li.task { list-style: none; }
.markdown li.task input { margin: 0 6px 0 -22px; vertical-align: middle; }
.markdown a { color: var(--accent); text-underline-offset: 2px; }
.markdown code { font-family: var(--mono); font-size: .9em; padding: 1px 5px; border-radius: 6px; background: var(--surface-alt); }
.markdown pre { overflow: auto; padding: 12px 14px; border-radius: var(--r-s); background: var(--surface-alt); }
.markdown pre code { padding: 0; background: none; font-size: 13px; line-height: 20px; }
.markdown blockquote { padding: 2px 0 2px 14px; border-left: 3px solid var(--outline); color: var(--muted); }
.markdown hr { border: 0; border-top: 1px solid var(--outline); margin: 20px 0; }
.markdown .table-wrap { overflow-x: auto; }
.markdown table { border-collapse: collapse; font-size: 14px; }
.markdown th, .markdown td { padding: 6px 10px; border: 1px solid var(--outline); text-align: left; vertical-align: top; }
.markdown th { background: var(--surface-alt); font-weight: 600; }
.markdown .al-c { text-align: center; }
.markdown .al-r { text-align: right; }

/* Transcripts */
.transcript-title { margin: 0 0 2px; font-size: 16px; line-height: 22px; font-weight: 600; }
.player { display: block; width: 100%; margin: 14px 0 4px; }
.segments { list-style: none; margin: 14px 0 0; padding: 0; display: grid; gap: 4px; }
.segment { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 2px 10px; align-items: baseline; }
.segment-body { overflow-wrap: anywhere; }
.ts { min-height: 32px; padding: 4px 6px; border: 1px solid transparent; border-radius: var(--r-s); background: none; color: var(--accent); font: 500 13px/20px var(--mono); cursor: pointer; }
.ts:hover { background: var(--accent-container); }
.ts-static { color: var(--muted); cursor: default; }
.segment.active .segment-body { background: var(--accent-container); border-radius: 4px; }
.speaker { font-weight: 600; margin-right: 6px; }

/* Files */
.files { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
.file { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; }
.file a { display: inline-flex; align-items: center; min-height: 32px; color: var(--accent); font-weight: 600; overflow-wrap: anywhere; text-underline-offset: 2px; }

@media (pointer: coarse) {
  .ts, .file a { min-height: 44px; }
}
@media (max-width: 760px) {
  .app { height: auto; min-height: 100vh; }
  .topbar { flex-wrap: wrap; gap: 10px; padding: 10px 16px; }
  .brand { flex: 1; }
  .search { order: 3; flex-basis: 100%; max-width: none; margin: 0; }
  .layout { grid-template-columns: minmax(0, 1fr); }
  .list-pane { max-height: 45vh; padding: 12px; border-right: 0; border-bottom: 1px solid var(--outline); }
  .detail { overflow: visible; padding: 20px 16px 48px; }
  .panel { padding: 16px; }
}
@media (prefers-reduced-motion: no-preference) {
  .doc-item, .btn, .ts { transition: background-color .12s ease, border-color .12s ease; }
}
`;

/** Client script. Plain ES2017, no template literals (it lives inside one). */
const CLIENT_JS = String.raw`
(function () {
  'use strict';
  // Identity shim in case a bundler wrapped nested functions with a name helper.
  var __name = function (target) { return target; };
  var renderMarkdown = (__RENDER_MARKDOWN__);
  void __name;

  var base = location.pathname.replace(/\/+$/, '');
  var api = base + '/api';
  var body = document.body;
  var $ = function (id) { return document.getElementById(id); };
  var loginView = $('login-view'), appView = $('app-view'), form = $('login-form'), pw = $('password');
  var loginError = $('login-error'), unlockBtn = $('unlock-btn'), lockBtn = $('lock-btn'), search = $('search');
  var list = $('doc-list'), listStatus = $('list-status'), detail = $('detail');
  var selectedId = null, searchTimer = 0, listSeq = 0, detailSeq = 0;
  var KINDS = { NOTEBOOK: 'Notebook', PDF: 'PDF', WHITEBOARD: 'Whiteboard', TYPED: 'Typed' };

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }
  function narrow() { return window.matchMedia('(max-width: 760px)').matches; }
  function plural(n, one, many) { n = n || 0; return n + ' ' + (n === 1 ? one : many); }
  function kindLabel(k) { return KINDS[k] || String(k || ''); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function fmtClock(ms) {
    var s = Math.max(0, Math.floor((ms || 0) / 1000));
    return pad(Math.floor(s / 60)) + ':' + pad(s % 60);
  }
  function fmtDate(ms, withTime) {
    if (!ms) return '';
    var o = { year: 'numeric', month: 'short', day: 'numeric' };
    if (withTime) { o.hour = '2-digit'; o.minute = '2-digit'; }
    try { return new Date(ms).toLocaleString(undefined, o); } catch (e) { return new Date(ms).toISOString().slice(0, 10); }
  }
  function fmtDuration(ms) {
    var s = Math.round((ms || 0) / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    if (h) return h + ' h ' + pad(m) + ' min';
    if (m) return m + ' min ' + pad(s % 60) + ' s';
    return (s % 60) + ' s';
  }
  function fmtSize(n) {
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(n < 10240 ? 1 : 0) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }
  function fileUrl(docId, name) {
    return api + '/docs/' + encodeURIComponent(docId) + '/files/' + encodeURIComponent(name);
  }
  function request(path) {
    return fetch(api + path, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } }).then(function (res) {
      if (res.status === 401) { showLogin('Your session ended. Unlock again.'); throw new Error('unauthorized'); }
      return res;
    });
  }

  function showLogin(message) {
    body.setAttribute('data-signed-in', 'false');
    appView.hidden = true;
    loginView.hidden = false;
    list.textContent = '';
    detail.textContent = '';
    selectedId = null;
    loginError.textContent = message || '';
    loginError.hidden = !message;
    pw.value = '';
    pw.focus();
  }
  function showApp() {
    body.setAttribute('data-signed-in', 'true');
    loginView.hidden = true;
    appView.hidden = false;
    loginError.hidden = true;
    showEmpty();
    loadDocs(true);
  }
  function showEmpty() {
    detail.textContent = '';
    detail.appendChild(el('p', 'empty', 'Select a document to read its notes, transcripts and files.'));
  }

  form.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var password = pw.value;
    if (!password) {
      loginError.textContent = 'Enter your password.';
      loginError.hidden = false;
      pw.focus();
      return;
    }
    unlockBtn.disabled = true;
    loginError.hidden = true;
    fetch(api + '/session', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify({ password: password })
    }).then(function (res) {
      if (res.ok) { pw.value = ''; showApp(); if (!narrow()) search.focus(); return; }
      return res.json().catch(function () { return {}; }).then(function (data) {
        var msg = 'Something went wrong. Try again.';
        if (res.status === 401) msg = 'That password is not right.';
        if (res.status === 429) {
          var min = Math.max(1, Math.ceil((data.retryAfterSeconds || 60) / 60));
          msg = 'Too many attempts. Try again in ' + plural(min, 'minute', 'minutes') + '.';
        }
        loginError.textContent = msg;
        loginError.hidden = false;
        pw.select();
        pw.focus();
      });
    }).catch(function () {
      loginError.textContent = 'Could not reach the vault. Check your connection.';
      loginError.hidden = false;
    }).then(function () { unlockBtn.disabled = false; });
  });

  lockBtn.addEventListener('click', function () {
    lockBtn.disabled = true;
    fetch(api + '/logout', { method: 'POST', credentials: 'same-origin' }).catch(function () {}).then(function () {
      lockBtn.disabled = false;
      try { history.replaceState(null, '', base); } catch (e) {}
      showLogin();
    });
  });

  search.addEventListener('input', function () {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(function () { loadDocs(false); }, 250);
  });
  search.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape' && search.value) { search.value = ''; loadDocs(false); }
  });

  function loadDocs(initial) {
    var q = search.value.trim();
    var seq = ++listSeq;
    list.setAttribute('aria-busy', 'true');
    request('/docs' + (q ? '?q=' + encodeURIComponent(q) : '')).then(function (res) {
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.json();
    }).then(function (data) {
      if (seq !== listSeq) return;
      renderList(data.docs || [], q);
      if (initial && !selectedId) {
        var m = /^#doc=([A-Za-z0-9-]{1,64})$/.exec(location.hash);
        if (m) selectDoc(m[1], false);
      }
    }).catch(function (e) {
      if (seq === listSeq && e.message !== 'unauthorized') listStatus.textContent = 'Could not load documents.';
    }).then(function () { if (seq === listSeq) list.removeAttribute('aria-busy'); });
  }

  function renderList(docs, q) {
    list.textContent = '';
    if (docs.length) listStatus.textContent = plural(docs.length, 'document', 'documents') + (q ? ' matching “' + q + '”' : '');
    else listStatus.textContent = q ? 'Nothing matches “' + q + '”.' : 'No documents yet. Sync from Inkwell to see them here.';
    docs.forEach(function (d) {
      var li = el('li');
      var b = el('button', 'doc-item');
      b.type = 'button';
      b.setAttribute('data-id', d.id);
      if (d.id === selectedId) b.setAttribute('aria-current', 'true');
      b.appendChild(el('span', 'doc-title', d.title || 'Untitled'));
      b.appendChild(el('span', 'doc-meta', fmtDate(d.createdAt) + ' · ' + kindLabel(d.kind)));
      b.appendChild(el('span', 'doc-counts', plural(d.transcriptCount, 'transcript', 'transcripts') + ' · ' + plural(d.fileCount, 'file', 'files')));
      b.addEventListener('click', function () { selectDoc(d.id, true); });
      li.appendChild(b);
      list.appendChild(li);
    });
  }

  function markSelected() {
    var items = list.querySelectorAll('.doc-item');
    for (var i = 0; i < items.length; i++) {
      if (items[i].getAttribute('data-id') === selectedId) items[i].setAttribute('aria-current', 'true');
      else items[i].removeAttribute('aria-current');
    }
  }

  function selectDoc(id, fromUser) {
    selectedId = id;
    markSelected();
    try { history.replaceState(null, '', '#doc=' + encodeURIComponent(id)); } catch (e) {}
    var seq = ++detailSeq;
    detail.setAttribute('aria-busy', 'true');
    request('/docs/' + encodeURIComponent(id)).then(function (res) {
      if (res.status === 404) return null;
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.json();
    }).then(function (doc) {
      if (seq !== detailSeq) return;
      if (!doc) {
        detail.textContent = '';
        detail.appendChild(el('p', 'empty', 'This document is no longer in the vault.'));
        return;
      }
      renderDetail(doc);
      if (fromUser && narrow()) { detail.focus({ preventScroll: true }); detail.scrollIntoView({ block: 'start' }); }
    }).catch(function (e) {
      if (seq !== detailSeq || e.message === 'unauthorized') return;
      detail.textContent = '';
      detail.appendChild(el('p', 'empty', 'Could not load this document.'));
    }).then(function () { if (seq === detailSeq) detail.removeAttribute('aria-busy'); });
  }

  function section(title, id) {
    var s = el('section', 'doc-section');
    s.setAttribute('aria-labelledby', id);
    var h = el('h3', 'section-title', title);
    h.id = id;
    s.appendChild(h);
    return s;
  }

  function renderDetail(doc) {
    detail.textContent = '';
    var art = el('article', 'doc');
    art.setAttribute('aria-labelledby', 'doc-heading');
    var head = el('header');
    var h = el('h2', 'doc-heading', doc.title || 'Untitled');
    h.id = 'doc-heading';
    head.appendChild(h);
    var meta = [kindLabel(doc.kind)];
    if (doc.folderPath) meta.push(doc.folderPath);
    if (doc.pageCount) meta.push(plural(doc.pageCount, 'page', 'pages'));
    meta.push('Created ' + fmtDate(doc.createdAt));
    meta.push('Modified ' + fmtDate(doc.modifiedAt, true));
    head.appendChild(el('p', 'meta', meta.join(' · ')));
    if (doc.tags && doc.tags.length) {
      var tags = el('ul', 'tags');
      tags.setAttribute('aria-label', 'Tags');
      doc.tags.forEach(function (t) { tags.appendChild(el('li', 'tag', t)); });
      head.appendChild(tags);
    }
    art.appendChild(head);

    var files = doc.files || [];
    var present = {};
    files.forEach(function (f) { present[f.name] = true; });

    var notes = section('Notes', 'sec-notes');
    var md = el('div', 'panel markdown');
    if (doc.markdown && doc.markdown.trim()) md.innerHTML = renderMarkdown(doc.markdown);
    else md.appendChild(el('p', 'muted', 'No recognised text for this document yet.'));
    notes.appendChild(md);
    art.appendChild(notes);

    var transcripts = doc.transcripts || [];
    if (transcripts.length) {
      var ts = section('Transcripts', 'sec-transcripts');
      transcripts.forEach(function (t) { ts.appendChild(renderTranscript(doc.id, t, present)); });
      art.appendChild(ts);
    }

    var fs = section('Files', 'sec-files');
    var panel = el('div', 'panel');
    if (files.length) {
      var ul = el('ul', 'files');
      files.forEach(function (f) {
        var li = el('li', 'file');
        var a = el('a', null, f.name);
        a.href = fileUrl(doc.id, f.name);
        a.setAttribute('download', f.name);
        li.appendChild(a);
        li.appendChild(el('span', 'meta', (f.contentType || 'file') + ' · ' + fmtSize(f.size || 0) + ' · ' + fmtDate(f.uploadedAt, true)));
        ul.appendChild(li);
      });
      panel.appendChild(ul);
    } else {
      panel.appendChild(el('p', 'muted', 'No files uploaded.'));
    }
    fs.appendChild(panel);
    art.appendChild(fs);
    detail.appendChild(art);
  }

  function renderTranscript(docId, t, present) {
    var box = el('div', 'panel transcript');
    box.appendChild(el('h4', 'transcript-title', t.title || 'Recording'));
    var meta = [fmtDate(t.startedAt, true), fmtDuration(t.durationMs)];
    if (t.source) meta.push(t.source);
    box.appendChild(el('p', 'meta', meta.join(' · ')));
    var audioName = 'rec_' + t.recordingId + '.m4a';
    var audio = null;
    if (present[audioName]) {
      audio = document.createElement('audio');
      audio.className = 'player';
      audio.controls = true;
      audio.preload = 'metadata';
      audio.src = fileUrl(docId, audioName);
      audio.setAttribute('aria-label', 'Recording: ' + (t.title || 'audio'));
      box.appendChild(audio);
    }
    var ol = el('ol', 'segments');
    var rows = [];
    (t.segments || []).forEach(function (s) {
      var li = el('li', 'segment');
      var label = '[' + fmtClock(s.startMs) + ']';
      if (audio) {
        var b = el('button', 'ts', label);
        b.type = 'button';
        b.setAttribute('aria-label', 'Play from ' + fmtClock(s.startMs));
        b.addEventListener('click', function () { seek(audio, s.startMs); });
        li.appendChild(b);
      } else {
        li.appendChild(el('span', 'ts ts-static', label));
      }
      var text = el('span', 'segment-body');
      if (s.speaker) text.appendChild(el('span', 'speaker', s.speaker + ':'));
      text.appendChild(document.createTextNode(s.text || ''));
      li.appendChild(text);
      ol.appendChild(li);
      rows.push({ li: li, start: s.startMs || 0 });
    });
    box.appendChild(ol);
    if (audio && rows.length) {
      var active = null;
      audio.addEventListener('timeupdate', function () {
        var now = audio.currentTime * 1000, hit = null;
        for (var i = 0; i < rows.length && rows[i].start <= now; i++) hit = rows[i].li;
        if (hit !== active) {
          if (active) active.classList.remove('active');
          if (hit) hit.classList.add('active');
          active = hit;
        }
      });
    }
    return box;
  }

  function seek(audio, ms) {
    var go = function () {
      try { audio.currentTime = (ms || 0) / 1000; } catch (e) {}
      var p = audio.play();
      if (p && p.catch) p.catch(function () {});
    };
    if (audio.readyState >= 1) go();
    else { audio.addEventListener('loadedmetadata', go, { once: true }); audio.load(); }
  }

  if (body.getAttribute('data-signed-in') === 'true') { showEmpty(); loadDocs(true); }
  else pw.focus();
})();
`;

function clientScript(): string {
  const md = renderMarkdown.toString();
  // split/join rather than String.replace: the renderer source contains "$1" patterns.
  return CLIENT_JS.split("__RENDER_MARKDOWN__").join(md).replace(/<\/(script)/gi, "<\\/$1").replace(/<!--/g, "<\\!--");
}

export function renderPage(nonce: string, signedIn: boolean): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<meta name="referrer" content="no-referrer">
<meta name="color-scheme" content="light dark">
<title>Notes</title>
<link rel="icon" href="data:,">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700&amp;display=swap">
<style nonce="${nonce}">${CSS}</style>
</head>
<body data-signed-in="${signedIn ? "true" : "false"}">
<main id="login-view" class="login"${signedIn ? " hidden" : ""}>
  <div class="card">
    <h1>${LOCK_ICON}<span>Notes</span></h1>
    <form id="login-form" novalidate>
      <label class="label" for="password">Password</label>
      <input class="input" id="password" name="password" type="password" autocomplete="current-password" required spellcheck="false" aria-describedby="login-error">
      <p id="login-error" class="error" role="alert" hidden></p>
      <button id="unlock-btn" class="btn primary" type="submit">Unlock</button>
    </form>
    <p class="hint">This private link is only for you.</p>
    <noscript><p class="error">This page needs JavaScript.</p></noscript>
  </div>
</main>
<div id="app-view" class="app"${signedIn ? "" : " hidden"}>
  <header class="topbar">
    <h1 class="brand">${LOCK_ICON}<span>Your Pit Box · Notes</span></h1>
    <div class="search" role="search">
      <label class="visually-hidden" for="search">Search notes and transcripts</label>
      <input class="input" id="search" type="search" placeholder="Search notes and transcripts" autocomplete="off" spellcheck="false">
    </div>
    <button id="lock-btn" class="btn" type="button">Lock</button>
  </header>
  <div class="layout">
    <nav class="list-pane" aria-label="Synced documents">
      <p id="list-status" class="list-status" role="status" aria-live="polite"></p>
      <ul id="doc-list" class="doc-list"></ul>
    </nav>
    <main id="detail" class="detail" tabindex="-1"></main>
  </div>
</div>
<script nonce="${nonce}">${clientScript()}</script>
</body>
</html>
`;
}
