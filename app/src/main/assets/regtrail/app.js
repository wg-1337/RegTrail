/*
 * RegTrail floating window front-end.
 *
 * Three rules govern this file:
 *   1. It must never throw. It runs inside a third-party app's process, and an
 *      uncaught error would leave the user with a blank window and no clue.
 *      Every entry point is wrapped.
 *   2. The native bridge is optional. If `RegTrailNative` is missing (page
 *      opened for a preview, or the interface failed to attach) the UI degrades
 *      to a read-only shell instead of breaking.
 *   3. Native code owns everything that matters — the window size, the log ring,
 *      the script list. This file asks and renders; it never assumes a value and
 *      never keeps the only copy of one.
 */
(function () {
  'use strict';

  var Native = window.RegTrailNative || null;

  /* ---- Bridge --------------------------------------------------------- */

  /** Sends one message through the native bridge; returns its raw reply or null. */
  function call(action, payload) {
    if (!Native || typeof Native.post !== 'function') return null;
    try {
      return Native.post(action, JSON.stringify(payload || {}));
    } catch (e) {
      return null;
    }
  }

  /**
   * Sends one message and parses the reply.
   *
   * Native answers every action that returns a value with
   * `{"ok":true,"data":{...}}` or `{"ok":false,"error":"..."}`. `null` means
   * "there is no bridge" (preview outside an injected process); an unparsable
   * reply is reported as an error rather than silently ignored.
   */
  function request(action, payload) {
    var raw = call(action, payload);
    if (!raw || raw === 'null') return null;
    try {
      return JSON.parse(raw);
    } catch (e) {
      return { ok: false, error: '原生返回内容不是 JSON' };
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
      /*
       * `overlayCornerDp` is dp and `--regtrail-corner` is used as a CSS length.
       * CSS pixels *are* dp inside the WebView (the viewport is at scale 1), so
       * the number can be used as-is — appending "px" would be wrong but harmless,
       * appending "dp" would be ignored. This matches the previous behaviour.
       */
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

  /** Last size native code reported, used only when the DOM cannot be measured. */
  var lastNativeSize = null;

  /** Mirrors the native collapsed state; gates the grip and the log poller. */
  var collapsed = false;

  function setStatus(data) {
    if (!data || !data.data) return;
    var d = data.data;
    set('s-package', d.package);
    set('s-mechanism', mechanismLabel(d.mechanism));
    set('s-framework', d.framework);
    // The native status payload names this field `apiVersion`; reading `d.api`
    // (as an earlier version did) silently rendered an em dash.
    set('s-api', d.apiVersion);
    set('s-target-count', targetCountLabel(d));
    set('s-targets', Array.isArray(d.targets) && d.targets.length
      ? d.targets.join(', ')
      : '（全部已授权作用域）');

    if (typeof d.widthDp === 'number' && typeof d.heightDp === 'number') {
      lastNativeSize = { w: d.widthDp, h: d.heightDp };
    }
    if (typeof d.collapsed === 'boolean') setCollapsedState(d.collapsed);
    renderSize();
  }

  /**
   * How many apps the settings app ticked for the floating window.
   *
   * Preferred over the length of `targets`, which is the merged framework scope
   * and therefore can be much larger than what the user configured — showing the
   * scope count here would explain nothing about "why does this app have no
   * window".
   */
  function targetCountLabel(d) {
    if (typeof d.configuredTargetCount === 'number') return d.configuredTargetCount + ' 个';
    if (Array.isArray(d.targets)) return d.targets.length + ' 个（作用域）';
    return null;
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

  /**
   * Current window size in dp.
   *
   * Measured from the panel itself rather than tracked: the native side resizes
   * the WebView, and CSS pixels inside it are dp (the viewport is at scale 1), so
   * one measurement is both live and exact.
   */
  function liveSizeDp() {
    var panel = document.getElementById('panel');
    if (!panel || typeof panel.getBoundingClientRect !== 'function') return null;
    var rect = panel.getBoundingClientRect();
    return { w: Math.round(rect.width), h: Math.round(rect.height) };
  }

  function renderSize() {
    if (collapsed) {
      set('s-size', '已折叠');
      return;
    }
    var size = liveSizeDp();
    if (size && size.w > 0 && size.h > 0) {
      set('s-size', size.w + ' x ' + size.h + ' dp');
      return;
    }
    if (lastNativeSize) set('s-size', lastNativeSize.w + ' x ' + lastNativeSize.h + ' dp');
  }

  /* ---- Log ------------------------------------------------------------ */

  var MAX_LINES = 500;
  var LOG_POLL_MS = 1500;

  /** Non-null while the 日志 tab is visible. */
  var logTimer = null;

  /** Cheap "did anything change" check so an unchanged ring costs no DOM work. */
  var logSignature = '';

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

  function renderLogs(lines, meta) {
    var box = document.getElementById('log');
    if (!box) return;

    var view = box.parentNode;
    // Only auto-scroll when the user was already at the bottom; yanking the view
    // away while they are reading older lines is the classic log-panel annoyance.
    var atBottom = !view ||
      (view.scrollTop + view.clientHeight >= view.scrollHeight - 24);

    while (box.firstChild) box.removeChild(box.firstChild);
    for (var i = 0; i < lines.length; i++) {
      var div = document.createElement('div');
      div.className = 'line ' + severityClass(lines[i]);
      div.textContent = lines[i];
      box.appendChild(div);
    }

    if (view && atBottom) view.scrollTop = view.scrollHeight;
    updateLogHint(meta, lines.length);
  }

  function updateLogHint(meta, shown) {
    var hint = document.getElementById('log-hint');
    if (!hint) return;
    if (!Native) {
      hint.textContent = '原生接口不可用（预览模式）';
      return;
    }
    var capacity = meta && typeof meta.capacity === 'number' ? meta.capacity : 0;
    hint.textContent = '显示最近 ' + shown + ' 行' +
      (capacity ? ' / 内存缓冲 ' + capacity + ' 行' : '') +
      ' · 每 ' + (LOG_POLL_MS / 1000) + ' 秒刷新';
  }

  /* Polls only while 日志 is the visible tab and the window is expanded: an idle
     panel must not wake the JS bridge every 1.5 s for the lifetime of the host
     app's process. */
  function pollLogs() {
    if (collapsed || currentTab() !== 'log') return;
    var res = request('logs');
    if (!res) return;
    if (!res.ok || !res.data) return;

    var lines = res.data.lines || [];
    var signature = lines.length + '|' + (lines.length ? lines[lines.length - 1] : '');
    if (signature === logSignature) {
      updateLogHint(res.data, lines.length);
      return;
    }
    logSignature = signature;
    renderLogs(lines, res.data);
  }

  function startLogPolling() {
    if (logTimer || collapsed) return;
    pollLogs();
    logTimer = setInterval(pollLogs, LOG_POLL_MS);
  }

  function stopLogPolling() {
    if (!logTimer) return;
    clearInterval(logTimer);
    logTimer = null;
  }

  /* ---- Tabs ----------------------------------------------------------- */

  function initTabs() {
    var tabs = document.querySelectorAll('.tab');
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].addEventListener('click', function () {
        showTab(this.getAttribute('data-tab'));
      });
    }
  }

  function showTab(name) {
    var all = document.querySelectorAll('.tab');
    for (var i = 0; i < all.length; i++) {
      all[i].classList.toggle('active', all[i].getAttribute('data-tab') === name);
    }
    var views = document.querySelectorAll('.view');
    for (var j = 0; j < views.length; j++) {
      views[j].classList.toggle('active', views[j].getAttribute('data-view') === name);
    }
    if (name === 'log') startLogPolling(); else stopLogPolling();
  }

  function currentTab() {
    var active = document.querySelector('.view.active');
    return active ? active.getAttribute('data-view') : '';
  }

  /* ---- Buttons -------------------------------------------------------- */

  function initButtons() {
    on('btn-collapse', function () { request('collapse'); });
    on('btn-close', function () { request('hide'); });
    on('btn-hide', function () { hideWindow(); });
    // The 日志 tab's 清空 clears the module's ring as well: clearing only the DOM
    // would be undone by the next poll, which reads like a broken button.
    on('btn-clear', function () { clearLogs('已清空模块日志缓冲'); });
    on('btn-copy', copyLog);
    on('btn-refresh', refreshConfig);

    // 工具 tab.
    on('btn-tools-reload', refreshConfig);
    on('btn-tools-clear', function () { clearLogs('已清空模块日志缓冲'); });
    on('btn-tools-scripts', listScripts);
    on('btn-tools-hide', hideWindow);
  }

  function on(id, fn) {
    var el = document.getElementById(id);
    if (el) el.addEventListener('click', fn);
  }

  function refreshStatus() {
    var res = request('status');
    if (!res) return;
    try { setStatus(res); } catch (e) { /* ignore */ }
  }

  function refreshConfig() {
    setToolsStatus('正在重载配置…');
    var res = request('reloadConfig');
    if (!res) { setToolsStatus('原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setToolsStatus(res.error || '重载失败', true); return; }

    var targets = res.data && typeof res.data.targets === 'number' ? res.data.targets : null;
    setToolsStatus('配置已重载' +
      (targets === null ? '' : ' · ' + targets + ' 个悬浮窗目标'));
    refreshStatus();
  }

  function clearLogs(message) {
    var res = request('clearLogs');
    logSignature = '';
    var box = document.getElementById('log');
    if (box) box.innerHTML = '';
    if (!res) { setToolsStatus('原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setToolsStatus(res.error || '清空失败', true); return; }

    var dropped = res.data && typeof res.data.cleared === 'number' ? res.data.cleared : 0;
    setToolsStatus(message + '（' + dropped + ' 行）');
  }

  function copyLog() {
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
      setToolsStatus('已复制 ' + box.childNodes.length + ' 行日志');
    } catch (e) {
      // Clipboard access is best-effort inside a WebView without user activation.
      setToolsStatus('复制失败（WebView 不允许访问剪贴板）', true);
    }
  }

  function listScripts() {
    setToolsStatus('正在读取脚本…');
    var res = request('listScripts');
    if (!res) { setToolsStatus('原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setToolsStatus(res.error || '读取脚本失败', true); return; }

    var scripts = res.data && res.data.scripts ? res.data.scripts : [];
    renderScripts(scripts);
    setToolsStatus(scripts.length
      ? '共 ' + scripts.length + ' 个可运行脚本'
      : '没有启用的脚本');
  }

  function renderScripts(scripts) {
    var box = document.getElementById('scripts');
    if (!box) return;
    while (box.firstChild) box.removeChild(box.firstChild);

    if (!scripts.length) {
      var empty = document.createElement('p');
      empty.className = 'script empty';
      empty.textContent = '没有启用的脚本：在 RegTrail 的脚本页启用后会出现在这里。';
      box.appendChild(empty);
      return;
    }

    for (var i = 0; i < scripts.length; i++) {
      box.appendChild(scriptRow(scripts[i]));
    }
  }

  function scriptRow(script) {
    var row = document.createElement('div');
    row.className = 'script';

    var name = document.createElement('span');
    name.className = 'name';
    name.textContent = script.name || script.id;

    var meta = document.createElement('span');
    meta.className = 'meta';
    meta.textContent = triggerLabel(script.trigger) + ' · ' +
      (typeof script.actions === 'number' ? script.actions : 0) + ' 个动作';
    name.appendChild(meta);

    var run = document.createElement('button');
    run.className = 'run';
    run.textContent = '运行';
    run.addEventListener('click', function () { runScript(script, run); });

    row.appendChild(name);
    row.appendChild(run);
    return row;
  }

  /*
   * The trigger arrives as the `ScriptTrigger` enum name. Rendering it verbatim
   * would put an English identifier in an otherwise Chinese panel, so it is mapped
   * here; an unknown name is passed through rather than hidden, so a newer module
   * that adds a trigger still shows something meaningful.
   */
  function triggerLabel(trigger) {
    switch (trigger) {
      case 'Manual': return '手动';
      case 'AppAttach': return '应用 attach';
      case 'AppOnCreate': return '应用启动';
      case 'ActivityOnCreate': return 'Activity 创建';
      case 'ActivityOnResume': return 'Activity 恢复';
      case 'ClassLoaded': return '类加载';
      case 'MethodEnter': return '方法进入';
      default: return trigger || '手动';
    }
  }

  function runScript(script, button) {
    var label = script.name || script.id;
    setToolsStatus('正在运行 ' + label + ' …');
    if (button) button.disabled = true;

    var res = request('runScript', { id: script.id });

    if (button) button.disabled = false;
    if (!res) { setToolsStatus('原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setToolsStatus(res.error || '运行失败', true); return; }

    var result = res.data && res.data.result ? res.data.result : '已执行';
    // `runManual` reports failures as a Chinese sentence starting with 执行失败,
    // so the error styling is derived from the text rather than a flag.
    setToolsStatus(result, /失败/.test(result));
    refreshStatus();
  }

  function hideWindow() {
    // Say so *before* the window goes away in the overlay mechanism.
    setToolsStatus('窗口已隐藏（重启目标应用或由设置端重新显示）');
    request('hide');
  }

  function setToolsStatus(text, isError) {
    var el = document.getElementById('tools-status');
    if (!el) return;
    el.textContent = text || '';
    el.classList.toggle('error', !!isError);
  }

  /* ---- Resize grip ---------------------------------------------------- */

  /*
   * Mirror of `OverlaySizeLimits` on the native side. They are duplicated because
   * they live in different languages; the native clamp is authoritative, so a
   * mismatch can only make the page optimistic, never produce an unusable window.
   */
  var MIN_WIDTH_DP = 220;
  var MIN_HEIGHT_DP = 200;

  /* Pointer Events cover both fingers and mice and are universally available in
     Android's WebView since Chrome 55. Touch events are the fallback so the grip
     still works on a stripped ROM WebView. */
  var HAS_POINTER = typeof window.PointerEvent !== 'undefined';
  var EV_DOWN = HAS_POINTER ? 'pointerdown' : 'touchstart';
  var EV_MOVE = HAS_POINTER ? 'pointermove' : 'touchmove';
  var EV_UP = HAS_POINTER ? 'pointerup' : 'touchend';
  var EV_CANCEL = HAS_POINTER ? 'pointercancel' : 'touchcancel';

  function initGrip() {
    var grip = document.getElementById('grip');
    if (!grip) return;

    var active = false;
    var startX = 0;
    var startY = 0;
    var startW = 0;
    var startH = 0;
    var pending = null;
    var frame = 0;

    /*
     * Touch events carry the interesting coordinate in `touches` while moving and
     * in `changedTouches` when the finger lifts; pointer events carry it directly.
     */
    function point(ev, preferChanged) {
      var list = preferChanged ? ev.changedTouches : ev.touches;
      if (!list || !list.length) list = preferChanged ? ev.touches : ev.changedTouches;
      if (list && list.length) return list[0];
      return ev;
    }

    function compute(ev) {
      var p = point(ev, ev.type === EV_UP || ev.type === EV_CANCEL);
      return {
        w: Math.max(MIN_WIDTH_DP, Math.round(startW + (p.clientX - startX))),
        h: Math.max(MIN_HEIGHT_DP, Math.round(startH + (p.clientY - startY)))
      };
    }

    function begin(ev) {
      // The pill is not resizable and the page keeps that promise here as well as
      // on the native side.
      if (collapsed || active) return;
      var size = liveSizeDp();
      if (!size || !size.w || !size.h) return;

      var p = point(ev, false);
      active = true;
      startX = p.clientX;
      startY = p.clientY;
      startW = size.w;
      startH = size.h;
      document.body.classList.add('dragging');
      if (HAS_POINTER && grip.setPointerCapture) {
        try { grip.setPointerCapture(ev.pointerId); } catch (e) { /* not fatal */ }
      }
      if (ev.cancelable) ev.preventDefault();
    }

    function move(ev) {
      if (!active) return;
      if (ev.cancelable) ev.preventDefault();

      pending = compute(ev);
      // One bridge call per animation frame: `post` is a synchronous JNI call, so
      // every raw touch sample would be both wasteful and janky.
      if (frame) return;
      frame = requestAnimationFrame(function () {
        frame = 0;
        var size = pending;
        pending = null;
        if (size && active) sendResize(size.w, size.h, false);
      });
    }

    function end(ev) {
      if (!active) return;
      active = false;
      document.body.classList.remove('dragging');
      if (frame && window.cancelAnimationFrame) window.cancelAnimationFrame(frame);
      frame = 0;
      pending = null;

      var size = compute(ev);
      // `final: true` is what makes native code persist the size; intermediate
      // frames only resize the window so a drag does not write preferences on
      // every animation frame.
      sendResize(size.w, size.h, true);
    }

    grip.addEventListener(EV_DOWN, begin, false);
    window.addEventListener(EV_MOVE, move, { passive: false });
    window.addEventListener(EV_UP, end, false);
    window.addEventListener(EV_CANCEL, end, false);
  }

  function sendResize(widthDp, heightDp, isFinal) {
    var res = request('resize', {
      widthDp: widthDp,
      heightDp: heightDp,
      final: !!isFinal
    });
    if (!res) return null;
    if (!res.ok) {
      if (res.error) setToolsStatus(res.error, true);
      return res;
    }
    if (res.data && typeof res.data.widthDp === 'number' &&
        typeof res.data.heightDp === 'number') {
      // Adopt the clamped size so the next drag starts from reality, not from a
      // value the native side refused.
      lastNativeSize = { w: res.data.widthDp, h: res.data.heightDp };
      renderSize();
    }
    return res;
  }

  /* ---- Collapsed state ------------------------------------------------ */

  function setCollapsedState(value) {
    collapsed = !!value;
    document.body.classList.toggle('collapsed', collapsed);
    if (collapsed) {
      stopLogPolling();
    } else if (currentTab() === 'log') {
      startLogPolling();
    }
    renderSize();
  }

  /* ---- Native -> page API --------------------------------------------- */

  window.RegTrail = {
    onConfig: function (cfg) {
      try { applyConfig(cfg); } catch (e) { /* ignore */ }
    },
    onLog: function (line) {
      try { appendLog(line); } catch (e) { /* ignore */ }
    },
    onCollapsed: function (value) {
      // Native resizes the window to a pill and shows its own native pill view.
      // Nothing here must reload the page: the tab, the scrolled log and the
      // script list all have to survive a collapse/expand round trip.
      try { setCollapsedState(value); } catch (e) { /* ignore */ }
    }
  };

  /* ---- Boot ----------------------------------------------------------- */

  function boot() {
    try {
      initTabs();
      initButtons();
      initGrip();
      request('ready');
      refreshStatus();
      renderSize();
      // The native side pushes config right after attaching; poll once as a
      // safety net in case we were loaded after that push.
      setTimeout(refreshStatus, 400);
      // Native resizes the WebView itself (grip drag, config change, rotation),
      // which fires `resize` here — the cheapest way to keep the size readout
      // live without polling.
      window.addEventListener('resize', function () {
        try { renderSize(); } catch (e) { /* ignore */ }
      });
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
