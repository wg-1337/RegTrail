/*
 * RegTrail floating window front-end.
 *
 * Four rules govern this file:
 *   1. It must never throw. It runs inside a third-party app's process, and an
 *      uncaught error would leave the user with a blank window and no clue.
 *      Every entry point is wrapped.
 *   2. The native bridge is optional. If `RegTrailNative` is missing (page
 *      opened for a preview, or the interface failed to attach) the UI degrades
 *      to a read-only shell instead of breaking.
 *   3. Native code owns everything that matters — the window size, the log ring,
 *      the script list, the memory search session. This file asks and renders; it
 *      never assumes a value and never keeps the only copy of one.
 *   4. A long action never blocks the bridge. `Native.post` is synchronous, so a
 *      memory scan or a model turn is submitted as a *job* and polled; see
 *      `callJob`. The one exception is the resize drag, which deliberately applies
 *      its size locally first and only then tells native code.
 */
(function () {
  'use strict';

  var Native = window.RegTrailNative || null;

  /* ---- Tiny DOM helpers ------------------------------------------------- */

  function byId(id) {
    return document.getElementById(id);
  }

  /**
   * Creates one element.
   *
   * `textContent`, never `innerHTML`: this file renders data that came from
   * another process (class names, field values, memory contents). Building DOM
   * from strings would turn a hostile host app's class name into script.
   */
  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function raf(fn) {
    if (window.requestAnimationFrame) return window.requestAnimationFrame(fn);
    return setTimeout(fn, 16);
  }

  function setBusy(ids, busy) {
    for (var i = 0; i < ids.length; i++) {
      var node = byId(ids[i]);
      if (node) node.disabled = !!busy;
    }
  }

  /* ---- Bridge ----------------------------------------------------------- */

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

  /* ---- Long-running jobs ------------------------------------------------ */

  /*
   * A memory scan may read 64 MiB and an Agent turn may sit in a socket for a
   * minute. Both must not be awaited inside one `Native.post` call: that call
   * blocks the WebView's JavaBridge thread, so the page cannot repaint, cannot
   * respond to a tap, and looks frozen.
   *
   * The protocol is in `OverlayController` / `InProcessJobs`:
   *   post(action)            -> data with `pending:false` when it finished inline
   *                           -> data with `pending:true, job:N` otherwise
   *   post('jobPoll', {job:N})-> same shape, `progress` while it runs
   *   post('jobCancel', {job:N})
   */

  var POLL_MS = 250;

  /** In-flight jobs, keyed by the action that started them. */
  var pendingJobs = {};

  function callJob(action, payload, onDone, onProgress, timeoutMs) {
    var deadline = Date.now() + (timeoutMs || 60000);

    var res = request(action, payload);
    if (!res) {
      onDone({ ok: false, error: '原生接口不可用（预览模式）' });
      return 0;
    }
    if (!res.ok) {
      onDone(res);
      return 0;
    }

    var data = res.data || {};
    if (!data.pending) {
      onDone(res);
      return 0;
    }

    var jobId = typeof data.job === 'number' ? data.job : 0;
    if (!jobId) {
      onDone({ ok: false, error: '原生返回了无效的任务编号' });
      return 0;
    }

    pendingJobs[action] = { id: jobId, timer: 0 };
    if (onProgress) onProgress(data);
    schedulePoll(action, jobId, onDone, onProgress, deadline);
    return jobId;
  }

  function schedulePoll(action, jobId, onDone, onProgress, deadline) {
    var entry = pendingJobs[action];
    if (!entry) return;

    entry.timer = setTimeout(function () {
      if (!pendingJobs[action]) return; // cancelled meanwhile

      var res = request('jobPoll', { job: jobId });
      if (!res) {
        delete pendingJobs[action];
        onDone({ ok: false, error: '原生接口不可用（预览模式）' });
        return;
      }
      if (!res.ok) {
        delete pendingJobs[action];
        onDone(res);
        return;
      }

      var data = res.data || {};
      if (data.pending) {
        if (onProgress) onProgress(data);
        if (Date.now() > deadline) {
          request('jobCancel', { job: jobId });
          delete pendingJobs[action];
          onDone({ ok: false, error: '任务超时（已请求取消）' });
          return;
        }
        schedulePoll(action, jobId, onDone, onProgress, deadline);
        return;
      }

      delete pendingJobs[action];
      onDone(res);
    }, POLL_MS);
  }

  /** Stops polling and asks native code to abandon the work. */
  function cancelJob(action) {
    var entry = pendingJobs[action];
    if (!entry) return false;
    if (entry.timer) clearTimeout(entry.timer);
    request('jobCancel', { job: entry.id });
    delete pendingJobs[action];
    return true;
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

    applyPanels(cfg);
  }

  /**
   * Applies the published panel list.
   *
   * ## The compatibility rule
   * A `config.json` published by a build that predates the 内存/调试/Agent tabs names
   * only the panels it knew about. Honouring that literally would hide the tabs this
   * build adds, and a missing tab reads as "the feature was never integrated" — the
   * exact complaint this version answers. Such a list is therefore only honoured for
   * the panels it *does* name, and a tab this build added stays visible until the
   * settings app republishes a list that mentions it.
   */
  function applyPanels(cfg) {
    var panels = [];
    if (Object.prototype.toString.call(cfg.panels) === '[object Array]') {
      for (var i = 0; i < cfg.panels.length; i++) {
        if (typeof cfg.panels[i] === 'string') panels.push(cfg.panels[i]);
      }
    }
    if (!panels.length) return;

    var tabs = document.querySelectorAll('.tab');
    var firstVisible = '';
    var anyKnown = false;
    for (var t = 0; t < tabs.length; t++) {
      var name = tabs[t].getAttribute('data-tab');
      // A known publishing build names every tab it wants; one that predates a tab
      // says nothing about it, which must not be read as "hide it".
      var visible;
      if (panels.indexOf(name) >= 0) {
        visible = true;
        anyKnown = true;
      } else if (name === 'debug') {
        visible = true;
      } else if (panels.indexOf('mem') < 0 && panels.indexOf('agent') < 0) {
        // A list from before 内存/Agent existed: no opinion about either.
        visible = true;
      } else {
        visible = false;
      }
      tabs[t].style.display = visible ? '' : 'none';
      if (visible && !firstVisible) firstVisible = name;
    }
    if (!anyKnown) return;
    // The tab that was active may have just been hidden; leaving it active would
    // make `currentTab()` lie and keep a poller awake for nothing.
    if (firstVisible && panels.indexOf(currentTab()) < 0) showTab(firstVisible);
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

  /**
   * Resize limits, in dp, mirrored from `OverlaySizeLimits`.
   *
   * Kept as mutable state filled from native `status`/`resize` replies so the grip
   * can clamp *while the finger moves*. The native clamp stays authoritative; a
   * stale page can only be optimistic, never create an unreachable window.
   */
  var LIMITS = {
    minW: 220,
    minH: 200,
    maxW: 0, // 0 = unknown, do not clamp
    maxH: 0
  };

  function applyLimits(data) {
    if (!data) return;
    if (typeof data.minWidthDp === 'number' && data.minWidthDp > 0) {
      LIMITS.minW = data.minWidthDp;
    }
    if (typeof data.minHeightDp === 'number' && data.minHeightDp > 0) {
      LIMITS.minH = data.minHeightDp;
    }

    var maxW = typeof data.maxWidthDp === 'number' ? data.maxWidthDp : 0;
    var maxH = typeof data.maxHeightDp === 'number' ? data.maxHeightDp : 0;
    var fraction = typeof data.screenFraction === 'number' ? data.screenFraction : 0.9;
    if (!maxW && typeof data.screenWidthDp === 'number') {
      maxW = Math.floor(data.screenWidthDp * fraction);
    }
    if (!maxH && typeof data.screenHeightDp === 'number') {
      maxH = Math.floor(data.screenHeightDp * fraction);
    }
    if (maxW > 0) LIMITS.maxW = maxW;
    if (maxH > 0) LIMITS.maxH = maxH;
  }

  function clampWidthDp(value) {
    var min = LIMITS.minW;
    var max = LIMITS.maxW > 0 ? Math.max(min, LIMITS.maxW) : 0;
    var rounded = Math.round(value);
    if (rounded < min) return min;
    if (max > 0 && rounded > max) return max;
    return rounded;
  }

  function clampHeightDp(value) {
    var min = LIMITS.minH;
    var max = LIMITS.maxH > 0 ? Math.max(min, LIMITS.maxH) : 0;
    var rounded = Math.round(value);
    if (rounded < min) return min;
    if (max > 0 && rounded > max) return max;
    return rounded;
  }

  function setStatus(data) {
    if (!data || !data.data) return;
    var d = data.data;
    applyLimits(d);
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
    var node = byId(id);
    if (node) {
      node.textContent = (value === undefined || value === null || value === '')
        ? '—' : String(value);
    }
  }

  /**
   * Current window size in dp.
   *
   * Measured from the panel itself rather than tracked: the native side resizes
   * the WebView, and CSS pixels inside it are dp (the viewport is at scale 1), so
   * one measurement is both live and exact. While a drag is overriding the panel's
   * CSS size, this returns the override — which is what the drag maths wants.
   */
  function liveSizeDp() {
    var panel = byId('panel');
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
    var box = byId('log');
    if (!box) return;

    var div = el('div', 'line ' + severityClass(line), line);
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
    var box = byId('log');
    if (!box) return;

    var view = box.parentNode;
    // Only auto-scroll when the user was already at the bottom; yanking the view
    // away while they are reading older lines is the classic log-panel annoyance.
    var atBottom = !view ||
      (view.scrollTop + view.clientHeight >= view.scrollHeight - 24);

    while (box.firstChild) box.removeChild(box.firstChild);
    for (var i = 0; i < lines.length; i++) {
      box.appendChild(el('div', 'line ' + severityClass(lines[i]), lines[i]));
    }

    if (view && atBottom) view.scrollTop = view.scrollHeight;
    updateLogHint(meta, lines.length);
  }

  function updateLogHint(meta, shown) {
    var hint = byId('log-hint');
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
    // The 调试 tab polls call records; nothing else needs a timer.
    if (name === 'debug') startDebugPolling(); else stopDebugPolling();
    // Only 内存, 调试 and Agent have text fields; every other tab releases window
    // focus so the soft keyboard cannot linger over a read-only page.
    if (name !== 'mem' && name !== 'agent' && name !== 'debug') setImeFocus(false);
    // Cheap, non-blocking refreshes of the per-tab header lines. The heavy data
    // (a scan, a model turn) is always an explicit button press.
    if (name === 'mem') refreshMemoryState();
    if (name === 'agent') refreshAgentState();
    if (name === 'debug') refreshDebugHooks();
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
    var node = byId(id);
    if (node) node.addEventListener('click', fn);
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
    refreshAgentState();
  }

  function clearLogs(message) {
    var res = request('clearLogs');
    logSignature = '';
    var box = byId('log');
    if (box) box.innerHTML = '';
    if (!res) { setToolsStatus('原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setToolsStatus(res.error || '清空失败', true); return; }

    var dropped = res.data && typeof res.data.cleared === 'number' ? res.data.cleared : 0;
    setToolsStatus(message + '（' + dropped + ' 行）');
  }

  function copyLog() {
    var box = byId('log');
    if (!box) return;
    var text = box.innerText || '';
    try {
      var area = document.createElement('textarea');
      area.value = text;
      document.body.appendChild(area);
      area.select();
      document.execCommand('copy');
      document.body.removeChild(area);
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
    var box = byId('scripts');
    if (!box) return;
    while (box.firstChild) box.removeChild(box.firstChild);

    if (!scripts.length) {
      box.appendChild(el('p', 'script empty',
        '没有启用的脚本：在 RegTrail 的脚本页启用后会出现在这里。'));
      return;
    }

    for (var i = 0; i < scripts.length; i++) {
      box.appendChild(scriptRow(scripts[i]));
    }
  }

  function scriptRow(script) {
    var row = el('div', 'script');

    var name = el('span', 'name', script.name || script.id);
    name.appendChild(el('span', 'meta', triggerLabel(script.trigger) + ' · ' +
      (typeof script.actions === 'number' ? script.actions : 0) + ' 个动作'));

    var run = el('button', 'run', '运行');
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
    setLine('tools-status', text, isError);
  }

  function setLine(id, text, isError) {
    var node = byId(id);
    if (!node) return;
    node.textContent = text || '';
    node.classList.toggle('error', !!isError);
  }

  /* ---- Text input focus (soft keyboard) -------------------------------- */

  /*
   * A `FLAG_NOT_FOCUSABLE` overlay cannot take input focus, and the soft keyboard
   * attaches to the *focused window*: without this, every `<input>` in the panel
   * would show a caret and never raise a keyboard.
   *
   * Native code lifts `FLAG_NOT_FOCUSABLE` while a field is being edited and
   * restores it afterwards (and on BACK), so the panel keeps out of the host app's
   * way the rest of the time.
   */

  var imeFocusable = false;

  function setImeFocus(on) {
    if (imeFocusable === !!on) return;
    imeFocusable = !!on;
    request('imeFocus', { focused: imeFocusable });
  }

  function bindTextFocus(node) {
    if (!node || !node.addEventListener) return;

    var acquire = function () { setImeFocus(true); };
    node.addEventListener('focus', acquire);
    // `pointerdown` arrives *before* the browser focuses the field, which is the
    // only ordering that lets the keyboard appear on the very first tap.
    node.addEventListener('pointerdown', acquire);
    if (!HAS_POINTER) node.addEventListener('touchstart', acquire);

    node.addEventListener('blur', function () {
      // A tap on another field fires blur and then focus; releasing focusability
      // in between would drop the IME that is about to appear.
      setTimeout(function () {
        if (document.activeElement !== node) setImeFocus(false);
      }, 150);
    });
  }

  /* ---- Resize grip ---------------------------------------------------- */

  /*
   * The grip drives the window size in two directions at once:
   *
   *   1. **Locally, immediately.** The pointer delta is written straight into
   *      `#panel`'s CSS width/height, so the panel tracks the finger at the
   *      display's own refresh rate with no bridge call in the loop.
   *   2. **Natively, at most once per animation frame.** Only the latest size is
   *      sent, so a 120 Hz touch stream cannot flood the synchronous JNI bridge,
   *      and the size is persisted only on release (`final: true`).
   *
   * Before this, every pointer move waited for a synchronous `Native.post` whose
   * reply only arrived after `WindowManager.updateViewLayout` had run on the main
   * thread: one to three frames of lag behind the finger, which is exactly what
   * "大小调整操作手感反人类" describes.
   *
   * The clamp mirrors the native limits (min 220x200 dp, max 90 % of the screen)
   * so the drag hits a wall instead of fighting the native clamp with a value the
   * window will never take.
   */

  /* Pointer Events cover both fingers and mice and are universally available in
     Android's WebView since Chrome 55. Touch events are the fallback so the grip
     still works on a stripped ROM WebView. */
  var HAS_POINTER = typeof window.PointerEvent !== 'undefined';
  var EV_DOWN = HAS_POINTER ? 'pointerdown' : 'touchstart';
  var EV_MOVE = HAS_POINTER ? 'pointermove' : 'touchmove';
  var EV_UP = HAS_POINTER ? 'pointerup' : 'touchend';
  var EV_CANCEL = HAS_POINTER ? 'pointercancel' : 'touchcancel';

  /** Non-null while the page is overriding `#panel`'s CSS size during a drag. */
  var localSize = null;

  function applyLocalSize(widthDp, heightDp) {
    var panel = byId('panel');
    if (!panel) return;
    panel.style.width = widthDp + 'px';
    panel.style.height = heightDp + 'px';
    localSize = { w: widthDp, h: heightDp };
  }

  function clearLocalSize() {
    var panel = byId('panel');
    if (panel) {
      panel.style.width = '';
      panel.style.height = '';
    }
    localSize = null;
  }

  /**
   * Drops the local override once the WebView itself has caught up.
   *
   * The native window resize arrives asynchronously (`main.post` →
   * `updateViewLayout`), and the WebView's viewport follows it. Removing the
   * inline size before that makes the panel snap back to the *old* viewport for a
   * frame; removing it after is invisible. Comparing the viewport to the override
   * is the cheapest way to know.
   */
  function syncLocalSize() {
    if (!localSize) return;
    var w = window.innerWidth;
    var h = window.innerHeight;
    if (Math.abs(w - localSize.w) <= 2 && Math.abs(h - localSize.h) <= 2) {
      clearLocalSize();
    }
  }

  function initGrip() {
    var grip = byId('grip');
    if (!grip) return;

    var active = false;
    var startX = 0;
    var startY = 0;
    var startW = 0;
    var startH = 0;
    var latest = null;
    var frame = 0;
    var sentW = 0;
    var sentH = 0;

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
      if (!p || typeof p.clientX !== 'number' || typeof p.clientY !== 'number') return null;
      return {
        w: clampWidthDp(startW + (p.clientX - startX)),
        h: clampHeightDp(startH + (p.clientY - startY))
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
      sentW = size.w;
      sentH = size.h;
      latest = { w: size.w, h: size.h };

      // No text selection, no scroll gesture, no long-press callout while the
      // finger is on the handle.
      document.body.classList.add('dragging');

      if (HAS_POINTER && grip.setPointerCapture) {
        // Pointer capture is what keeps the move stream coming when the finger
        // leaves the 30 dp handle — without it the drag stops as soon as the
        // finger outruns the corner.
        try { grip.setPointerCapture(ev.pointerId); } catch (e) { /* not fatal */ }
      }
      if (ev.cancelable) ev.preventDefault();
    }

    function move(ev) {
      if (!active) return;
      if (ev.cancelable) ev.preventDefault();

      var size = compute(ev);
      if (!size) return;
      latest = size;

      // 1) the panel follows the finger right now
      applyLocalSize(size.w, size.h);

      // 2) native code hears about it once per frame, at most
      if (frame) return;
      frame = raf(function () {
        frame = 0;
        if (!active || !latest) return;
        if (latest.w === sentW && latest.h === sentH) return;
        sentW = latest.w;
        sentH = latest.h;
        sendResize(latest.w, latest.h, false);
      });
    }

    function end(ev) {
      if (!active) return;
      active = false;
      document.body.classList.remove('dragging');
      if (frame && window.cancelAnimationFrame) window.cancelAnimationFrame(frame);
      frame = 0;
      if (HAS_POINTER && grip.releasePointerCapture && ev && ev.pointerId !== undefined) {
        try { grip.releasePointerCapture(ev.pointerId); } catch (e) { /* not fatal */ }
      }

      var size = compute(ev) || latest;
      latest = null;
      if (!size) return;
      applyLocalSize(size.w, size.h);
      // `final: true` is what makes native code persist the size; intermediate
      // frames only resize the window so a drag does not write preferences on
      // every animation frame.
      sendResize(size.w, size.h, true);
    }

    /** A lost capture (finger off the screen, window change) ends the drag. */
    function lost(ev) {
      if (!active) return;
      end(ev);
    }

    grip.addEventListener(EV_DOWN, begin, false);
    window.addEventListener(EV_MOVE, move, { passive: false });
    window.addEventListener(EV_UP, end, false);
    window.addEventListener(EV_CANCEL, end, false);
    if (HAS_POINTER) {
      grip.addEventListener('lostpointercapture', lost, false);
      // `pointerup` outside the window (a finger that leaves the WebView) still
      // ends the drag through the capture callback above; this is the belt.
      grip.addEventListener('pointerup', end, false);
    }
  }

  /* ---- Header drag (moving the whole window) --------------------------- */

  /*
   * The expanded panel is moved by its **title bar**, never by its body: the body
   * has to keep every touch for tabs, lists, text fields and the memory result
   * rows. The collapsed pill keeps its own native drag handler; this one only ever
   * runs while the panel is expanded.
   *
   * ## Why the maths is not "delta from pointerdown"
   * The strategy is the resize grip's: draw locally first, tell native code at most
   * once per animation frame, persist on release. One thing is different, though —
   * for a *move* the WebView travels with the window, so every frame of native
   * movement shifts `clientX` back the other way. A naive delta would therefore
   * stall as soon as native code caught up (the panel stops tracking the finger).
   *
   * The panel's on-screen position is
   *
   *     native offset + local transform
   *
   * so the page asks native for the **total** delta `d` and draws `d - applied`
   * locally, where `applied` is what the last `dragMove` reply said native code
   * had really applied. `d` is the finger's travel in *screen* space:
   *
   *     d = (clientX - startClientX) + applied
   *
   * which is invariant to the window moving underneath. The panel therefore tracks
   * the finger with no lag at all, the real window is at most one frame behind, and
   * when native code clamps the window at the screen edge the page clamps `d` to
   * the bounds `dragBegin` reported — so the panel stops at the wall instead of
   * sliding away and snapping back on release.
   */
  var HEADER_SLOP_PX = 6;

  function initHeaderDrag() {
    var bar = byId('bar');
    if (!bar) return;

    var tracking = false;
    var active = false;
    var startX = 0;
    var startY = 0;
    var startScreenX = 0;
    var startScreenY = 0;
    var useScreen = false;
    var latest = { x: 0, y: 0 };
    var applied = { x: 0, y: 0 };
    var bounds = null;
    var frame = 0;
    var clearTimer = 0;

    function point(ev) {
      var list = ev.changedTouches;
      if ((!list || !list.length) && ev.touches && ev.touches.length) list = ev.touches;
      if (list && list.length) return list[0];
      if (typeof ev.clientX === 'number') return ev;
      return null;
    }

    function transformTo(dx, dy) {
      var panel = byId('panel');
      if (!panel) return;
      if (!dx && !dy) {
        panel.style.transform = '';
        return;
      }
      panel.style.transform =
        'translate3d(' + Math.round(dx) + 'px,' + Math.round(dy) + 'px,0)';
    }

    function clampDelta(value, min, max) {
      var v = Math.round(value);
      if (typeof min === 'number' && v < min) return min;
      if (typeof max === 'number' && v > max) return max;
      return v;
    }

    /**
     * Asks native code where the window may travel, once per gesture.
     *
     * Deferred until the finger has actually moved, so a plain tap on the title bar
     * costs no bridge round trip. A refused reply (`collapsed`, `not attached`,
     * preview mode) means "no drag", which is why the caller bails out on false.
     */
    function ensureBounds() {
      if (bounds) return true;
      var res = request('dragBegin');
      if (!res || !res.ok) return false;
      var data = res.data || {};
      bounds = {
        minDx: typeof data.minDx === 'number' ? data.minDx : null,
        maxDx: typeof data.maxDx === 'number' ? data.maxDx : null,
        minDy: typeof data.minDy === 'number' ? data.minDy : null,
        maxDy: typeof data.maxDy === 'number' ? data.maxDy : null
      };
      return true;
    }

    function pushDrag(isFinal) {
      var res = request('dragMove', { dx: latest.x, dy: latest.y, final: !!isFinal });
      if (!res || !res.ok) return;
      var data = res.data || {};
      if (typeof data.dx !== 'number' || typeof data.dy !== 'number') return;
      applied.x = data.dx;
      applied.y = data.dy;
      if (!isFinal) {
        // Redraw the residue: what is still left for the local transform once
        // native code has moved the window.
        transformTo(latest.x - applied.x, latest.y - applied.y);
      }
    }

    function begin(ev) {
      if (collapsed || tracking) return;

      // The title bar hosts the − and × buttons. A press that starts on one of
      // them belongs to that button: dragging the window from there would eat the
      // click, and the close button of a floating window is not a place to be
      // clever about gesture disambiguation.
      var target = ev.target;
      if (target && target.closest) {
        try {
          if (target.closest('button')) return;
        } catch (e) { /* ignore: a stripped WebView without closest() */ }
      }

      var p = point(ev);
      if (!p) return;

      tracking = true;
      active = false;
      startX = p.clientX;
      startY = p.clientY;
      // `screenX`/`screenY` are in *screen* pixels, so they do not move when the
      // window moves underneath the finger. That is the ideal measurement for a
      // window drag; the client-coordinate fallback has to be compensated with what
      // native code reports, and that compensation can overshoot by a frame.
      startScreenX = typeof p.screenX === 'number' ? p.screenX : p.clientX;
      startScreenY = typeof p.screenY === 'number' ? p.screenY : p.clientY;
      useScreen = typeof p.screenX === 'number' && typeof p.screenY === 'number';
      latest = { x: 0, y: 0 };
      applied = { x: 0, y: 0 };
      bounds = null;

      if (HAS_POINTER && bar.setPointerCapture && ev.pointerId !== undefined) {
        // Without capture the move stream stops the moment the finger leaves the
        // title bar, and a drag of a 300 dp panel leaves it immediately.
        try { bar.setPointerCapture(ev.pointerId); } catch (e) { /* not fatal */ }
      }
      // No text selection and no long-press callout from a press on the title bar.
      if (ev.cancelable) ev.preventDefault();
    }

    function move(ev) {
      if (!tracking) return;

      var p = point(ev);
      if (!p) return;

      if (!active) {
        // A tap, or a wobble while pressing, is not a drag.
        var slop = Math.abs(p.clientX - startX) + Math.abs(p.clientY - startY);
        if (slop < HEADER_SLOP_PX) return;
        if (!ensureBounds()) {
          tracking = false;
          return;
        }
        active = true;
        document.body.classList.add('moving');
      }

      if (ev.cancelable) ev.preventDefault();

      // Screen coordinates when the WebView reports them: the finger's travel is then
      // measured independently of the window moving, so no compensation term is
      // needed and the panel cannot drift.
      var rawX;
      var rawY;
      if (useScreen) {
        rawX = p.screenX - startScreenX;
        rawY = p.screenY - startScreenY;
        if (rawX === 0 && rawY === 0 &&
            (Math.abs(p.clientX - startX) + Math.abs(p.clientY - startY)) >= HEADER_SLOP_PX) {
          // screenX is present but constant on this WebView build: fall back for the
          // rest of the gesture rather than freezing the panel.
          useScreen = false;
        }
      }
      if (!useScreen) {
        rawX = applied.x + (p.clientX - startX);
        rawY = applied.y + (p.clientY - startY);
      }

      var wantX = clampDelta(rawX, bounds.minDx, bounds.maxDx);
      var wantY = clampDelta(rawY, bounds.minDy, bounds.maxDy);
      latest.x = wantX;
      latest.y = wantY;
      transformTo(wantX - applied.x, wantY - applied.y);

      // At most one native message per animation frame: a 120 Hz touch stream must
      // not flood the synchronous JS bridge.
      if (frame) return;
      frame = raf(function () {
        frame = 0;
        if (!tracking || !active) return;
        pushDrag(false);
      });
    }

    function finish(ev) {
      if (!tracking) return;
      tracking = false;

      var wasActive = active;
      active = false;
      document.body.classList.remove('moving');
      if (frame && window.cancelAnimationFrame) window.cancelAnimationFrame(frame);
      frame = 0;

      if (!wasActive) {
        bounds = null;
        transformTo(0, 0);
        return;
      }

      // The release event carries the finger's final position; without this the window
      // would stop at the last *move* event and end a few pixels short of where the
      // user let go.
      var p = ev ? point(ev) : null;
      if (p) {
        var endX = useScreen ? (p.screenX - startScreenX) : (applied.x + (p.clientX - startX));
        var endY = useScreen ? (p.screenY - startScreenY) : (applied.y + (p.clientY - startY));
        if (typeof endX === 'number' && typeof endY === 'number') {
          latest.x = clampDelta(endX, bounds.minDx, bounds.maxDx);
          latest.y = clampDelta(endY, bounds.minDy, bounds.maxDy);
        }
      }

      // `final: true` is what makes native code persist the position; per-frame
      // messages only move the window.
      pushDrag(true);
      // Drop the local override once the native layout has certainly been applied.
      // Clearing it in the same frame would snap the panel back for one frame; the
      // delay is imperceptible and only matters when native code clamped the move.
      if (clearTimer) clearTimeout(clearTimer);
      clearTimer = setTimeout(function () {
        clearTimer = 0;
        bounds = null;
        transformTo(0, 0);
      }, 140);

      if (HAS_POINTER && bar.releasePointerCapture && ev && ev.pointerId !== undefined) {
        try { bar.releasePointerCapture(ev.pointerId); } catch (e) { /* not fatal */ }
      }
    }

    bar.addEventListener(EV_DOWN, begin, false);
    window.addEventListener(EV_MOVE, move, { passive: false });
    window.addEventListener(EV_UP, finish, false);
    window.addEventListener(EV_CANCEL, finish, false);
    if (HAS_POINTER) {
      // A pointer that leaves the window entirely never reports `pointerup` here;
      // the lost-capture callback is what ends that drag.
      bar.addEventListener('lostpointercapture', finish, false);
      bar.addEventListener('pointerup', finish, false);
    }
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

    var data = res.data || {};
    applyLimits(data);
    if (typeof data.widthDp === 'number' && typeof data.heightDp === 'number') {
      lastNativeSize = { w: data.widthDp, h: data.heightDp };
      // The native clamp is authoritative: if it disagreed with what the page just
      // drew (a rotation mid-drag, a stale screen-size guess), show the size the
      // window really became instead of leaving the finger and the frame
      // disagreeing.
      if (data.widthDp !== widthDp || data.heightDp !== heightDp) {
        applyLocalSize(data.widthDp, data.heightDp);
      }
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
      clearLocalSize();
      // A 112 dp pill has no text fields: it must not hold the keyboard.
      setImeFocus(false);
    } else if (currentTab() === 'log') {
      startLogPolling();
    }
    renderSize();
  }

  /* ---- Memory tab ----------------------------------------------------- */

  /*
   * A search session lives on the native side (see `MemorySessions`): 首次扫描
   * starts one, 再次扫描 filters it. The page only ever holds the *rendered* rows,
   * so a WebView reload cannot lose the candidate addresses.
   *
   * Rendering is capped and chunked: a scan may legitimately return 500 hits, and
   * piping all of them into the DOM at once inside a 300 dp window both freezes the
   * panel for a frame and produces a list nobody reads.
   */
  var MEM_MAX_RENDERED = 200;
  var MEM_CHUNK = 80;
  var MEM_TIMEOUT_MS = 90000;

  var memSession = 0;
  var memRenderToken = 0;
  var memModified = 0;
  /**
   * The type the current session was started with.
   *
   * Remembered here as well as echoed by native: a resulting row is edited later
   * (possibly after a `memSessions` refresh that no longer carries the type), and
   * writing with the wrong width would corrupt two neighbouring values instead of
   * one. Falls back to the selector, then to `int32`.
   */
  var memType = 'int32';

  function initMemory() {
    on('btn-mem-scan', function () { runMemoryScan(true); });
    on('btn-mem-refine', function () { runMemoryScan(false); });
    on('btn-mem-revert-all', revertAllMemory);
    on('btn-mem-clear', clearMemorySession);
    on('btn-mem-probe', runMemoryProbe);

    var value = byId('mem-value');
    if (value) {
      bindTextFocus(value);
      value.addEventListener('keydown', function (ev) {
        // Enter = 首次扫描: searching a value then reaching for the button is the
        // one interaction that repeats hundreds of times per investigation.
        if (ev.key === 'Enter') { ev.preventDefault(); runMemoryScan(true); }
      });
    }
    refreshMemoryState();
  }

  /**
   * 诊断: asks native code to explain, from inside the host process, whether memory
   * access can work at all.
   *
   * The reply is plain text on purpose. When the answer is "this ROM's app domain is
   * not allowed to open /proc/self/mem", nothing the panel can do makes a scan work,
   * and the honest response is to show the evidence instead of a generic failure.
   * The lines are also appended to the 日志 pane (and logged natively), because that
   * is the copy the user can paste into a report.
   */
  function runMemoryProbe() {
    setLine('mem-status', '正在诊断内存访问…');

    var res = request('memProbe');
    if (!res) { setLine('mem-status', '原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setLine('mem-status', res.error || '诊断失败', true); return; }

    var data = res.data || {};
    var text = typeof data.text === 'string' ? data.text : '（空结果）';

    var out = byId('mem-probe-out');
    if (out) {
      out.textContent = text;
      out.style.display = 'block';
    }

    var lines = text.split('\n');
    for (var i = 0; i < lines.length; i++) {
      if (lines[i]) appendLog(lines[i]);
    }

    setLine('mem-status', '诊断完成 · 结果见上方与「日志」页');
  }

  /** Drops the search sessions; native keeps the recorded originals for 还原. */
  function clearMemorySession() {    var res = request('memClear');
    if (!res) { setLine('mem-status', '原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setLine('mem-status', res.error || '清空失败', true); return; }

    memSession = 0;
    memRenderToken++;
    var box = byId('mem-results');
    if (box) {
      while (box.firstChild) box.removeChild(box.firstChild);
    }
    var note = byId('mem-note');
    if (note) note.textContent = '';
    setLine('mem-status', '已清空搜索会话（已记录的原始值仍可用「还原」恢复）');
    refreshMemoryState();
  }

  function runMemoryScan(isFirst) {
    var typeNode = byId('mem-type');
    var valueNode = byId('mem-value');
    // A `<select>` with nothing selected reports '', which native code would
    // reject as an unknown type. Defaulting here keeps a stripped-down WebView
    // from turning a scan into a confusing error.
    var type = typeNode && typeNode.value ? typeNode.value : 'int32';
    var value = valueNode ? (valueNode.value || '').trim() : '';

    // One scan at a time. The Enter key path bypasses the disabled buttons, and a
    // second 64 MiB scan on top of a running one would just halve both.
    var running = isFirst ? 'memScan' : 'memRefine';
    if (pendingJobs[running]) {
      setLine('mem-status', '上一次扫描还在进行，可以先「停止」或等它结束', true);
      return;
    }

    if (!value) {
      setLine('mem-status', '请输入要搜索的值', true);
      if (valueNode) valueNode.focus();
      return;
    }
    if (!isFirst && !memSession) {
      setLine('mem-status', '请先做一次「首次扫描」', true);
      return;
    }

    var writableNode = byId('mem-writable');
    var writable = !!(writableNode && writableNode.checked);
    var action = isFirst ? 'memScan' : 'memRefine';
    var payload = isFirst
      ? { type: type, value: value, writableOnly: writable }
      : { session: memSession, value: value, type: type };

    setLine('mem-status', isFirst
      ? '正在扫描本进程内存（' + type + ' = ' + value + '）…'
      : '正在复核会话 #' + memSession + '（' + type + ' = ' + value + '）…');
    setBusy(['btn-mem-scan', 'btn-mem-refine'], true);
    memType = type;

    callJob(action, payload, function (res) {
      setBusy(['btn-mem-scan', 'btn-mem-refine'], false);
      if (!res) {
        setLine('mem-status', '原生接口不可用（预览模式）', true);
        return;
      }
      if (!res.ok) {
        setLine('mem-status', res.error || '扫描失败', true);
        return;
      }
      var data = res.data || {};
      if (typeof data.session === 'number' && data.session > 0) memSession = data.session;
      renderMemoryResults(data);
      refreshMemoryState();
    }, function (data) {
      if (data && data.progress) setLine('mem-status', data.progress);
    }, MEM_TIMEOUT_MS);
  }

  function renderMemoryResults(data) {
    var box = byId('mem-results');
    if (!box) return;

    var results = Object.prototype.toString.call(data.results) === '[object Array]'
      ? data.results : [];
    var total = typeof data.count === 'number' ? data.count : results.length;
    if (typeof data.type === 'string' && data.type) memType = data.type;
    var token = ++memRenderToken;

    while (box.firstChild) box.removeChild(box.firstChild);

    if (!results.length) {
      box.appendChild(el('p', 'hint',
        '没有命中。数值类型要区分正负与小数写法；字符串区分大小写，' +
        '而且 utf8 搜索不会命中 UTF-16 常量池里的字符串。'));
    }

    var limit = Math.min(results.length, MEM_MAX_RENDERED);
    var index = 0;

    function chunk() {
      // A newer scan started: this render is stale, stop touching the DOM.
      if (token !== memRenderToken) return;
      var end = Math.min(index + MEM_CHUNK, limit);
      for (; index < end; index++) {
        box.appendChild(memoryRow(results[index], data));
      }
      if (index < limit) raf(chunk);
    }
    chunk();

    if (total > limit) {
      box.appendChild(el('p', 'hint',
        '共 ' + total + ' 条，仅渲染前 ' + limit + ' 条（地址升序）。' +
        '用「再次扫描」缩小范围比逐条翻更有效。'));
    }

    var note = byId('mem-note');
    if (note) {
      note.textContent = (data.note || '') +
        (data.truncated ? '　（结果已截断）' : '');
    }
    setLine('mem-status', '命中 ' + total + ' 条' +
      (typeof data.bytesRead === 'number' ? ' · 读取 ' + mibText(data.bytesRead) : ''));
  }

  function mibText(bytes) {
    if (typeof bytes !== 'number' || bytes <= 0) return '0 KiB';
    if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + ' KiB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MiB';
  }

  function memoryRow(match, scanData) {
    var row = el('div', 'mem-row');
    row.appendChild(el('span', 'addr', match && match.addr ? match.addr : '?'));
    var valueSpan = el('span', 'val',
      match && match.value !== undefined ? match.value : '?');
    row.appendChild(valueSpan);

    // The type is fixed by the session, not by the row: native echoes it on a
    // scan reply, and `memType` covers a row rendered from a reply that did not.
    var kind = (scanData && scanData.type) || memType;

    var edit = el('button', 'mini', '改');
    edit.addEventListener('click', function () {
      toggleMemoryEditor(row, match, kind, valueSpan);
    });
    row.appendChild(edit);
    return row;
  }

  function toggleMemoryEditor(row, match, kind, valueSpan) {
    // Only one editor at a time: several open editors in a 200-row list is a
    // scroll-position minefield.
    var existing = document.querySelectorAll('.mem-edit');
    var mine = row.nextSibling && row.nextSibling.className === 'mem-edit'
      ? row.nextSibling : null;
    for (var i = 0; i < existing.length; i++) {
      if (existing[i] !== mine && existing[i].parentNode) {
        existing[i].parentNode.removeChild(existing[i]);
      }
    }
    if (mine) {
      row.parentNode.removeChild(mine);
      return;
    }

    var box = el('div', 'mem-edit');
    var input = document.createElement('input');
    input.className = 'input';
    input.type = 'text';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.value = match && match.value !== undefined ? String(match.value) : '';
    box.appendChild(input);

    var actions = el('div', 'row');
    actions.style.margin = '0';
    actions.style.flex = '1 1 100%';

    var writeBtn = el('button', 'mini danger', '写入');
    var revertBtn = el('button', 'mini', '还原');
    actions.appendChild(writeBtn);
    actions.appendChild(revertBtn);
    box.appendChild(actions);

    writeBtn.addEventListener('click', function () {
      confirmMemoryWrite(box, match, input, kind, valueSpan);
    });
    revertBtn.addEventListener('click', function () {
      // 还原 does not need a value: it restores the bytes the first write
      // replaced, which native code remembered.
      writeMemory(match.addr, kind, null, 'revert', match, valueSpan);
      row.parentNode.removeChild(box);
    });

    row.parentNode.insertBefore(box, row.nextSibling);
    try { input.focus(); } catch (e) { /* keyboard is best-effort */ }
  }

  /**
   * The mandatory confirmation step before a memory write.
   *
   * A write into the host app can corrupt its state with no undo other than
   * 「还原」, and the addresses come from a fuzzy search. The product plan requires
   * an explicit user confirmation for exactly this reason, so the first tap only
   * *arms* the write: the address and value are repeated back, and the confirming
   * button is styled as dangerous and is a different target from the one the user
   * just pressed.
   */
  function confirmMemoryWrite(box, match, input, kind, valueSpan) {
    var value = (input.value || '').trim();
    if (!value) {
      setLine('mem-status', '请输入要写入的值', true);
      return;
    }

    while (box.firstChild) box.removeChild(box.firstChild);

    var bar = el('div', 'mem-confirm');
    bar.appendChild(el('span', 'text',
      '确认写入 ' + match.addr + ' = ' + value + '（' + kind + '）？' +
      '这会立即改变目标应用的运行状态，写入前会记录原始值，「还原」可以恢复。'));

    var confirm = el('button', 'mini danger', '确认写入');
    var cancel = el('button', 'mini', '取消');
    confirm.addEventListener('click', function () {
      setLine('mem-status', '正在写入 ' + match.addr + ' …');
      writeMemory(match.addr, kind, value, 'write', match, valueSpan);
      if (box.parentNode) box.parentNode.removeChild(box);
    });
    cancel.addEventListener('click', function () {
      if (box.parentNode) box.parentNode.removeChild(box);
      setLine('mem-status', '已取消写入');
    });

    bar.appendChild(confirm);
    bar.appendChild(cancel);
    box.appendChild(bar);
  }

  function writeMemory(addr, type, value, mode, match, valueSpan) {
    var res = mode === 'revert'
      ? request('memRevert', { addr: addr })
      : request('memWrite', { addr: addr, type: type, value: value });

    if (!res) {
      setLine('mem-status', '原生接口不可用（预览模式）', true);
      return;
    }
    if (!res.ok) {
      setLine('mem-status', res.error || (mode === 'revert' ? '还原失败' : '写入失败'), true);
      return;
    }

    var data = res.data || {};
    if (mode === 'revert') {
      setLine('mem-status', '已还原 ' + (data.addr || addr) +
        '（剩余修改 ' + (typeof data.modifiedCount === 'number' ? data.modifiedCount : 0) + ' 处）');
      if (valueSpan && typeof data.value === 'string') valueSpan.textContent = data.value;
      if (match && typeof data.value === 'string') match.value = data.value;
    } else {
      setLine('mem-status', '已写入 ' + (data.addr || addr) + ' = ' + (data.value || value) +
        (data.verified === false ? '（回读不一致！）' : '（回读一致）'));
      if (valueSpan) valueSpan.textContent = data.value || value;
      if (match) match.value = data.value || value;
    }
    refreshMemoryState();
  }

  /**
   * Reverts every modified address, behind an explicit two-step confirmation.
   *
   * ## Why not `window.confirm`
   * The injected WebView has no `WebChromeClient`, so the built-in dialog has
   * nothing to draw it: `confirm()` returns false immediately and the button
   * silently does nothing. Every confirmation in this panel is therefore in-page.
   *
   * The disarm timer matters as much as the confirmation: the button sits at the
   * bottom of a list the user may be scrolling, and a stray second tap minutes
   * later must not rewrite the host app's memory.
   */
  function revertAllMemory() {
    var button = byId('btn-mem-revert-all');
    if (!button) return;

    if (memModified <= 0) {
      setLine('mem-status', '没有被修改过的地址');
      return;
    }

    if (button.getAttribute('data-confirm') === '1') {
      button.removeAttribute('data-confirm');
      sendRevertAll();
      return;
    }

    button.setAttribute('data-confirm', '1');
    button.textContent = '确认还原 ' + memModified + ' 处（再点一次）';
    setLine('mem-status',
      '再点一次才会把 ' + memModified + ' 个地址改回写入前的原始值（6 秒后自动取消）');
    setTimeout(function () {
      if (button.getAttribute('data-confirm') !== '1') return;
      button.removeAttribute('data-confirm');
      button.textContent = '还原全部修改（' + memModified + '）';
    }, 6000);
  }

  function sendRevertAll() {
    var res = request('memRevert', { addr: 'all' });
    if (!res) { setLine('mem-status', '原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setLine('mem-status', res.error || '还原失败', true); return; }

    var count = res.data && typeof res.data.reverted === 'number' ? res.data.reverted : 0;
    setLine('mem-status', '已还原 ' + count + ' 处修改');

    // Every rendered value is now stale, and native code cannot hand back the new
    // ones cheaply (a revert rewrites memory the sessions only cached values for).
    // Saying so is more honest than printing numbers that may be wrong.
    memRenderToken++;
    var box = byId('mem-results');
    if (box) {
      while (box.firstChild) box.removeChild(box.firstChild);
      box.appendChild(el('p', 'hint', '已还原全部修改：列表里的值可能已经变化，请重新扫描。'));
    }
    refreshMemoryState();
  }

  /** Refreshes the modified-count badge and the 「还原全部」 button state. */
  function refreshMemoryState() {
    var res = request('memSessions');
    if (!res || !res.ok || !res.data) return;
    var data = res.data;
    memModified = typeof data.modifiedCount === 'number' ? data.modifiedCount : 0;

    var button = byId('btn-mem-revert-all');
    if (button) {
      if (button.getAttribute('data-confirm') !== '1') {
        button.textContent = '还原全部修改（' + memModified + '）';
      }
      button.disabled = memModified <= 0;
    }
  }

  /* ---- Debug tab (real-time method watching) --------------------------- */

  /*
   * A live view of one method's calls: arguments and return value, newest last.
   *
   * ## Why polling and not a push channel
   * The bridge only goes one way (`Native.post` is page → native), so the page has to
   * ask. It asks *incrementally*: every reply carries the last `seq` it saw, the next
   * request sends it as `since`, and native code answers with only the newer entries.
   * That keeps a 700 ms tick cheap even for a method called in a loop, and it means a
   * dropped poll can never lose an entry or duplicate one.
   *
   * ## The two caps
   * Native code keeps 200 entries per hook; this page renders at most 150 rows. Both
   * caps exist for the same reason: watching a hot method must not grow the host
   * app's heap or freeze a 300 dp panel.
   */
  var DEBUG_MAX_ROWS = 150;
  var DEBUG_POLL_MS = 700;

  /** Active hooks as native code reports them. */
  var debugHooks = [];

  /** Per-hook cursor: the last recorded sequence number this page has seen. */
  var debugCursors = {};

  var debugTimer = null;

  function initDebug() {
    on('btn-debug-search', runDebugSearch);
    on('btn-debug-clear', clearDebugRecords);

    var input = byId('debug-query');
    if (input) {
      bindTextFocus(input);
      input.addEventListener('keydown', function (ev) {
        if (ev.key === 'Enter') { ev.preventDefault(); runDebugSearch(); }
      });
    }
    refreshDebugHooks();
  }

  function runDebugSearch() {
    var input = byId('debug-query');
    var query = input ? (input.value || '').trim() : '';
    if (!query) {
      setLine('debug-status', '请输入类名或方法名', true);
      if (input) input.focus();
      return;
    }

    setLine('debug-status', '正在搜索 ' + query + ' …');
    var res = request('debugSearch', { query: query });
    if (!res) { setLine('debug-status', '原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setLine('debug-status', res.error || '搜索失败', true); return; }

    var data = res.data || {};
    renderDebugMethods(data);
    setLine('debug-status', '找到 ' + (data.count || 0) + ' 个方法 · 扫描 ' +
      (data.classesScanned || 0) + ' 个类 · ' + (data.elapsedMs || 0) + ' ms' +
      (data.truncated ? '（结果已截断）' : ''));
  }

  function renderDebugMethods(data) {
    var box = byId('debug-methods');
    if (!box) return;
    while (box.firstChild) box.removeChild(box.firstChild);

    var methods = Object.prototype.toString.call(data.methods) === '[object Array]'
      ? data.methods : [];
    if (!methods.length) {
      box.appendChild(el('p', 'hint',
        '没有匹配的方法。搜索会先按类名匹配，再（在时间预算内）按方法名匹配；' +
        '用「类名#方法名」可以精确定位。'));
      return;
    }
    for (var i = 0; i < methods.length; i++) {
      box.appendChild(debugMethodRow(methods[i]));
    }
  }

  function debugMethodRow(method) {
    var row = el('div', 'mem-row');
    var params = Object.prototype.toString.call(method.params) === '[object Array]'
      ? method.params : [];
    var label = (method.className || '?') + '#' + (method.methodName || '?') +
      '(' + params.join(', ') + ')';
    row.appendChild(el('span', 'val', label));

    var button = el('button', 'mini', '监听');
    button.addEventListener('click', function () {
      button.disabled = true;
      installDebugHook(method, button);
    });
    row.appendChild(button);
    return row;
  }

  function installDebugHook(method, button) {
    setLine('debug-status', '正在监听 ' + (method.methodName || '?') + ' …');

    var res = request('debugHook', {
      className: method.className,
      methodName: method.methodName,
      params: method.params || [],
      mode: 'trace'
    });

    if (button) button.disabled = false;
    if (!res) { setLine('debug-status', '原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setLine('debug-status', res.error || '监听失败', true); return; }

    var data = res.data || {};
    setLine('debug-status', '已监听 #' + data.hookId + ' · ' + (data.target || ''));
    refreshDebugHooks();
    startDebugPolling();
  }

  function refreshDebugHooks() {
    var box = byId('debug-hooks');
    var res = request('debugHooks');
    if (!res) {
      if (box) {
        while (box.firstChild) box.removeChild(box.firstChild);
      }
      return;
    }
    if (!res.ok) return;

    var data = res.data || {};
    debugHooks = Object.prototype.toString.call(data.hooks) === '[object Array]'
      ? data.hooks : [];
    if (!box) return;

    while (box.firstChild) box.removeChild(box.firstChild);
    if (!debugHooks.length) return;

    for (var i = 0; i < debugHooks.length; i++) {
      box.appendChild(debugHookRow(debugHooks[i]));
    }
  }

  function debugHookRow(hook) {
    var row = el('div', 'hook-row');
    var info = el('div', 'hook-info', hook.target || hook.label || hook.id || '?');
    info.appendChild(el('span', 'count',
      '#' + (hook.hookId || hook.id) + ' · 调用 ' + (hook.calls || 0) +
      ' 次 · 已记录 ' + (hook.recorded || 0) + ' 条'));
    row.appendChild(info);

    var unhook = el('button', 'mini danger', '解除');
    unhook.addEventListener('click', function () {
      unhook.disabled = true;
      removeDebugHook(hook.hookId || 0, unhook);
    });
    row.appendChild(unhook);
    return row;
  }

  function removeDebugHook(hookId, button) {
    if (!hookId) {
      if (button) button.disabled = false;
      setLine('debug-status', '这个 Hook 不是本页安装的，请在 Agent 控制台用 unhook 解除', true);
      return;
    }
    var res = request('debugUnhook', { hookId: hookId });
    if (button) button.disabled = false;
    if (!res) { setLine('debug-status', '原生接口不可用（预览模式）', true); return; }
    if (!res.ok) { setLine('debug-status', res.error || '解除失败', true); return; }

    delete debugCursors[hookId];
    setLine('debug-status', '已解除 Hook #' + hookId);
    refreshDebugHooks();
    if (!debugHooks.length) stopDebugPolling();
  }

  function startDebugPolling() {
    if (debugTimer || collapsed) return;
    pollDebugCalls();
    debugTimer = setInterval(pollDebugCalls, DEBUG_POLL_MS);
  }

  function stopDebugPolling() {
    if (!debugTimer) return;
    clearInterval(debugTimer);
    debugTimer = null;
  }

  function pollDebugCalls() {
    if (collapsed || currentTab() !== 'debug') return;
    if (!debugHooks.length) return;

    for (var i = 0; i < debugHooks.length; i++) {
      var hook = debugHooks[i];
      if (!hook.hookId) continue;

      var since = debugCursors[hook.hookId] || 0;
      var res = request('debugCalls', { hookId: hook.hookId, since: since });
      if (!res || !res.ok) continue;

      var data = res.data || {};
      if (typeof data.next === 'number') debugCursors[hook.hookId] = data.next;
      if (typeof data.calls === 'number') hook.calls = data.calls;

      var entries = Object.prototype.toString.call(data.entries) === '[object Array]'
        ? data.entries : [];
      for (var j = 0; j < entries.length; j++) {
        appendDebugCall(hook, entries[j]);
      }
    }

    // Call counts are the reason this is a live view; refresh them at the poll rate
    // rather than only when a hook is installed.
    refreshDebugHooks();
  }

  function shortTarget(target) {
    var text = String(target || '?');
    // `com.foo.Bar#baz(int)` → `Bar#baz(int)`: the package is the least useful part
    // in a 300 dp panel, and the hash is what identifies the method.
    var hash = text.indexOf('#');
    if (hash < 0) return text;
    var head = text.slice(0, hash);
    var dot = head.lastIndexOf('.');
    return (dot >= 0 ? head.slice(dot + 1) : head) + text.slice(hash);
  }

  function timeText(millis) {
    if (typeof millis !== 'number' || millis <= 0) return '--:--:--';
    var date = new Date(millis);
    function pad(v, width) {
      var s = String(v);
      while (s.length < width) s = '0' + s;
      return s;
    }
    return pad(date.getHours(), 2) + ':' + pad(date.getMinutes(), 2) + ':' +
      pad(date.getSeconds(), 2) + '.' + pad(date.getMilliseconds(), 3);
  }

  function appendDebugCall(hook, entry) {
    var box = byId('debug-calls');
    if (!box || !entry) return;

    var row = el('div', 'call');
    var head = el('div', 'call-head');
    head.appendChild(el('span', 'tag', shortTarget(hook.target || hook.label)));
    head.appendChild(el('span', 'meta',
      timeText(entry.at) + ' · ' + (entry.thread || '?') +
      (typeof entry.ms === 'number' ? ' · ' + entry.ms + ' ms' : '')));
    row.appendChild(head);

    var args = Object.prototype.toString.call(entry.args) === '[object Array]'
      ? entry.args : [];
    row.appendChild(el('div', 'call-line', '参数: ' +
      (args.length ? args.join(', ') : '（无）')));

    if (entry.threw !== null && entry.threw !== undefined && entry.threw !== '') {
      row.appendChild(el('div', 'call-line err', '抛出: ' + entry.threw));
    } else {
      var value = (entry.result === null || entry.result === undefined)
        ? '（void）' : entry.result;
      row.appendChild(el('div', 'call-line ret', '返回值: ' + value));
    }

    box.appendChild(row);
    while (box.childNodes.length > DEBUG_MAX_ROWS) {
      box.removeChild(box.firstChild);
    }

    var view = document.querySelector('.view[data-view="debug"]');
    if (view) view.scrollTop = view.scrollHeight;
  }

  function clearDebugRecords() {
    for (var i = 0; i < debugHooks.length; i++) {
      var id = debugHooks[i].hookId;
      if (!id) continue;
      request('debugClear', { hookId: id });
      debugCursors[id] = 0;
    }
    var box = byId('debug-calls');
    if (box) {
      while (box.firstChild) box.removeChild(box.firstChild);
    }
    setLine('debug-status', '已清空调用记录（Hook 仍然生效）');
    refreshDebugHooks();
  }

  /* ---- Agent tab ------------------------------------------------------ */

  /*
   * Two entry points, and the UI says which one is live:
   *
   *  - the chat box runs a real ReAct loop **inside this process** against an
   *    OpenAI-compatible endpoint, using the same tools the console exposes
   *    (`InProcessAgent`, native side);
   *  - the console calls one tool with hand-written JSON. It works even when no
   *    model is configured or the host app has no INTERNET permission, which is
   *    the honest fallback rather than a dead chat box.
   */
  var AGENT_MAX_LINES = 120;
  var AGENT_TIMEOUT_MS = 300000;

  function initAgent() {
    on('btn-agent-send', sendAgentTurn);
    on('btn-agent-reset', resetAgentConversation);
    on('btn-agent-stop', function () {
      if (cancelJob('agentChat')) {
        setLine('agent-status', '已请求停止（模型请求可能仍会返回，结果会被丢弃）');
      } else {
        setLine('agent-status', '当前没有正在运行的对话');
      }
    });
    on('btn-console-run', runConsoleTool);

    var input = byId('agent-input');
    if (input) {
      bindTextFocus(input);
      input.addEventListener('keydown', function (ev) {
        if (ev.key === 'Enter') { ev.preventDefault(); sendAgentTurn(); }
      });
    }
    var consoleInput = byId('console-input');
    if (consoleInput) {
      bindTextFocus(consoleInput);
      consoleInput.addEventListener('keydown', function (ev) {
        if (ev.key === 'Enter') { ev.preventDefault(); runConsoleTool(); }
      });
    }
    refreshAgentState();
  }

  function refreshAgentState() {
    var hint = byId('agent-state');
    var res = request('agentState');
    if (!res || !res.ok || !res.data) {
      if (hint) {
        hint.textContent = '原生接口不可用（预览模式）：只能查看，不能调用工具。';
        hint.classList.add('error');
      }
      return;
    }

    var data = res.data;
    var parts = [];
    if (!data.configured) {
      parts.push('Agent 未配置：在设置端填写 Base URL / 模型 / API Key 后点「重载配置」；' +
        '下面的工具控制台现在就能用');
    } else {
      parts.push('模型 ' + (data.model || '?') + ' · ' + (data.baseUrl || '?'));
      parts.push('工具 ' + ((data.tools || []).length) + ' 个');
      if (typeof data.messages === 'number') parts.push('对话 ' + data.messages + ' 条消息');
      if (!data.internet) {
        parts.push('目标应用没有 INTERNET 权限，对话不可用');
      }
    }
    if (hint) {
      hint.textContent = parts.join(' · ');
      hint.classList.toggle('error', !data.configured || !data.internet);
    }
  }

  function sendAgentTurn() {
    var input = byId('agent-input');
    if (!input) return;
    var text = (input.value || '').trim();
    if (!text) {
      setLine('agent-status', '请输入要问 Agent 的内容', true);
      return;
    }
    // The Enter key path bypasses the disabled button; a second turn queued on the
    // same conversation would interleave two histories.
    if (pendingJobs['agentChat']) {
      setLine('agent-status', '上一轮还在进行；可以「停止」或等它结束', true);
      return;
    }

    var destructiveNode = byId('agent-destructive');
    var allowDestructive = !!(destructiveNode && destructiveNode.checked);

    appendAgentMessage('user', text);
    input.value = '';
    setLine('agent-status', 'Agent 正在思考…');
    setBusy(['btn-agent-send'], true);

    callJob('agentChat', {
      message: text,
      allowDestructive: allowDestructive
    }, function (res) {
      setBusy(['btn-agent-send'], false);
      if (!res) {
        appendAgentMessage('error', '原生接口不可用（预览模式）');
        setLine('agent-status', '未连接', true);
        return;
      }
      if (!res.ok) {
        appendAgentMessage('error', res.error || 'Agent 调用失败');
        setLine('agent-status', res.error || '调用失败', true);
        return;
      }

      var data = res.data || {};
      if (Object.prototype.toString.call(data.steps) === '[object Array]' &&
          data.steps.length) {
        appendAgentMessage('tool', data.steps.join('\n'));
      }
      appendAgentMessage('assistant', data.reply || '（空回复）');
      setLine('agent-status', '完成' +
        (data.allowDestructive ? '（本次允许破坏性操作）' : ''));
      refreshAgentState();
    }, function (data) {
      if (data && data.progress) setLine('agent-status', data.progress);
    }, AGENT_TIMEOUT_MS);
  }

  function appendAgentMessage(role, text) {
    var log = byId('agent-log');
    if (!log) return;

    log.appendChild(el('div', 'msg ' + role, text));
    while (log.childNodes.length > AGENT_MAX_LINES) {
      log.removeChild(log.firstChild);
    }

    var view = document.querySelector('.view[data-view="agent"]');
    if (view) view.scrollTop = view.scrollHeight;
  }

  function resetAgentConversation() {
    var res = request('agentReset');
    var log = byId('agent-log');
    if (log) {
      while (log.firstChild) log.removeChild(log.firstChild);
    }
    if (!res) {
      setLine('agent-status', '原生接口不可用（预览模式）', true);
      return;
    }
    if (!res.ok) {
      setLine('agent-status', res.error || '清空失败', true);
      return;
    }
    setLine('agent-status', '对话已清空');
    refreshAgentState();
  }

  /**
   * Runs one tool from the console: `工具名 {json}`.
   *
   * The JSON is passed through untouched; a parse error is reported here rather
   * than silently sending `{}`, because "why did list_classes ignore my pattern"
   * is a wasted debugging round trip.
   */
  function runConsoleTool() {
    var input = byId('console-input');
    var out = byId('console-out');
    if (!input || !out) return;

    var text = (input.value || '').trim();
    if (!text) return;

    var space = text.indexOf(' ');
    var name = space < 0 ? text : text.slice(0, space);
    var rest = space < 0 ? '' : text.slice(space + 1).trim();

    var args = {};
    if (rest) {
      try {
        args = JSON.parse(rest);
      } catch (e) {
        out.textContent = '参数不是合法 JSON：' + e.message;
        setLine('agent-status', '参数不是合法 JSON', true);
        return;
      }
    }

    setLine('agent-status', '正在执行 ' + name + ' …');
    var res = request('agentTool', { name: name, args: args });

    if (!res) {
      out.textContent = '原生接口不可用（预览模式）';
      setLine('agent-status', '未连接', true);
      return;
    }
    if (!res.ok) {
      out.textContent = res.error || '工具执行失败';
      setLine('agent-status', res.error || '工具执行失败', true);
      return;
    }

    var data = res.data || {};
    out.textContent = data.text || '（空结果）';
    setLine('agent-status',
      name + ' ' + (data.toolOk ? '完成' : '失败') + ' · ' + (data.text || '').length + ' 字符',
      !data.toolOk);
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
      // Nothing here must reload the page: the tab, the scrolled log, the script
      // list, the memory results and the Agent conversation all have to survive a
      // collapse/expand round trip.
      try { setCollapsedState(value); } catch (e) { /* ignore */ }
    }
  };

  /* ---- Boot ----------------------------------------------------------- */

  function boot() {
    try {
      initTabs();
      initButtons();
      initGrip();
      initHeaderDrag();
      initMemory();
      initDebug();
      initAgent();
      request('ready');
      refreshStatus();
      renderSize();
      // The native side pushes config right after attaching; poll once as a
      // safety net in case we were loaded after that push.
      setTimeout(refreshStatus, 400);
      // Native resizes the WebView itself (grip drag, config change, rotation),
      // which fires `resize` here — the cheapest way to keep the size readout
      // live, and the moment the local drag override can be dropped.
      window.addEventListener('resize', function () {
        try {
          syncLocalSize();
          renderSize();
        } catch (e) { /* ignore */ }
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
