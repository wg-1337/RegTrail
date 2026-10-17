/*
 * RegTrail floating window front-end.
 *
 * Two rules govern this file:
 *   1. It must never throw. It runs inside a third-party app's process, and an
 *      uncaught error would leave the user with a blank window and no clue.
 *      Every entry point is wrapped.
 *   2. The native bridge is optional. If `RegTrailNative` is missing (page
 *      opened for a preview, or the interface failed to attach) the UI degrades
 *      to a read-only shell instead of breaking.
 */
(function () {
  'use strict';

  var Native = window.RegTrailNative || null;

  function call(action, payload) {
    if (!Native || typeof Native.post !== 'function') return null;
    try {
      return Native.post(action, JSON.stringify(payload || {}));
    } catch (e) {
      return null;
    }
  }

  /* ---- Theme ---------------------------------------------------------- */

  function applyConfig(cfg) {
    if (!cfg) return;
    var root = document.documentElement;

    if (typeof cfg.overlayPrimary === 'number') {
      root.style.setProperty('--regtrail-primary', argbToCss(cfg.overlayPrimary));
    }
    if (typeof cfg.overlayFontScale === 'number') {
      root.style.setProperty('--regtrail-font-scale', String(cfg.overlayFontScale));
    }
    if (typeof cfg.overlayAlpha === 'number') {
      root.style.setProperty('--regtrail-alpha', String(cfg.overlayAlpha));
    }
    if (typeof cfg.overlayCornerDp === 'number') {
      root.style.setProperty('--regtrail-corner', cfg.overlayCornerDp + 'px');
    }

    // Panels list controls which tabs are offered.
    if (Array.isArray(cfg.panels) && cfg.panels.length) {
      var tabs = document.querySelectorAll('.tab');
      for (var i = 0; i < tabs.length; i++) {
        var name = tabs[i].getAttribute('data-tab');
        tabs[i].style.display = cfg.panels.indexOf(name) >= 0 ? '' : 'none';
      }
    }
  }

  /* Kotlin packs colours as a signed 32-bit ARGB int; JS sees it as a plain
     number that may be negative. Normalise to #RRGGBB. */
  function argbToCss(value) {
    var n = value >>> 0; // force unsigned 32-bit
    var r = (n >> 16) & 0xff;
    var g = (n >> 8) & 0xff;
    var b = n & 0xff;
    return '#' + hex(r) + hex(g) + hex(b);
  }

  function hex(v) {
    var s = v.toString(16);
    return s.length === 1 ? '0' + s : s;
  }

  /* ---- Status --------------------------------------------------------- */

  function setStatus(data) {
    if (!data || !data.data) return;
    var d = data.data;
    set('s-package', d.package);
    set('s-mechanism', mechanismLabel(d.mechanism));
    set('s-framework', d.framework);
    // The native status payload names this field `apiVersion`; reading `d.api`
    // (as an earlier version did) silently rendered an em dash.
    set('s-api', d.apiVersion);
    set('s-targets', Array.isArray(d.targets) && d.targets.length
      ? d.targets.join(', ')
      : '（全部已授权作用域）');
  }

  function mechanismLabel(name) {
    switch (name) {
      case 'ApplicationOverlay': return '应用级悬浮窗（免权限）';
      case 'ActivityDecor': return 'Activity 依附（降级）';
      default: return '未挂载';
    }
  }

  function set(id, value) {
    var el = document.getElementById(id);
    if (el) el.textContent = (value === undefined || value === null || value === '')
      ? '—' : String(value);
  }

  /* ---- Log ------------------------------------------------------------ */

  var MAX_LINES = 500;

  function appendLog(line) {
    var box = document.getElementById('log');
    if (!box) return;

    var div = document.createElement('div');
    div.className = 'line ' + severityClass(line);
    div.textContent = line;
    box.appendChild(div);

    while (box.childNodes.length > MAX_LINES) {
      box.removeChild(box.firstChild);
    }
    box.parentNode.scrollTop = box.parentNode.scrollHeight;
  }

  function severityClass(line) {
    // Lines look like "10-17 01:23:45.678 W message".
    if (/\sW\s/.test(line)) return 'w';
    if (/\sE\s/.test(line)) return 'e';
    if (/\sI\s/.test(line)) return 'i';
    return '';
  }

  /* ---- Tabs ----------------------------------------------------------- */

  function initTabs() {
    var tabs = document.querySelectorAll('.tab');
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].addEventListener('click', function () {
        var name = this.getAttribute('data-tab');
        var all = document.querySelectorAll('.tab');
        for (var j = 0; j < all.length; j++) {
          all[j].classList.toggle('active', all[j] === this);
        }
        var views = document.querySelectorAll('.view');
        for (var k = 0; k < views.length; k++) {
          views[k].classList.toggle(
            'active', views[k].getAttribute('data-view') === name);
        }
      });
    }
  }

  /* ---- Buttons -------------------------------------------------------- */

  function initButtons() {
    on('btn-collapse', function () { call('collapse'); });
    on('btn-close', function () { call('hide'); });
    on('btn-hide', function () { call('hide'); });
    on('btn-clear', function () {
      var box = document.getElementById('log');
      if (box) box.innerHTML = '';
    });
    on('btn-copy', function () {
      var box = document.getElementById('log');
      if (!box) return;
      var text = box.innerText || '';
      try {
        var ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      } catch (e) { /* clipboard is best-effort inside a WebView */ }
    });
    on('btn-refresh', function () {
      call('reloadConfig');
      setTimeout(refreshStatus, 150);
    });
  }

  function on(id, fn) {
    var el = document.getElementById(id);
    if (el) el.addEventListener('click', fn);
  }

  function refreshStatus() {
    var raw = call('status');
    if (!raw) return;
    try { setStatus(JSON.parse(raw)); } catch (e) { /* ignore */ }
  }

  /* ---- Native -> page API --------------------------------------------- */

  window.RegTrail = {
    onConfig: function (cfg) {
      try { applyConfig(cfg); } catch (e) { /* ignore */ }
    },
    onLog: function (line) {
      try { appendLog(line); } catch (e) { /* ignore */ }
    },
    onCollapsed: function (collapsed) {
      // Nothing to do visually; the native side swaps the window size and the
      // collapsed pill is a native view.
      void collapsed;
    }
  };

  /* ---- Boot ----------------------------------------------------------- */

  function boot() {
    try {
      initTabs();
      initButtons();
      call('ready');
      refreshStatus();
      // The native side pushes config right after attaching; poll once as a
      // safety net in case we were loaded after that push.
      setTimeout(refreshStatus, 400);
    } catch (e) {
      // Last resort: show something rather than an empty panel.
      try { appendLog('UI init failed: ' + e); } catch (ignored) { /* noop */ }
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
