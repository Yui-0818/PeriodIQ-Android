(function () {
  'use strict';

  var KEY = 'piq.theme';
  var VALID = { system: true, light: true, dark: true };
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  var mode = readMode();

  function readMode() {
    try {
      var stored = localStorage.getItem(KEY);
      return VALID[stored] ? stored : 'light';
    } catch (e) {
      return 'light';
    }
  }

  function resolved(nextMode) {
    if (nextMode === 'dark' || nextMode === 'light') return nextMode;
    return media && media.matches ? 'dark' : 'light';
  }

  function syncSystemBars(forceDark) {
    var dark = forceDark === undefined ? resolved(mode) === 'dark' : Boolean(forceDark);
    try {
      if (window.PIQNative && typeof window.PIQNative.setTheme === 'function') {
        window.PIQNative.setTheme(dark);
      }
    } catch (e) { /* 桌面浏览器没有宿主桥 */ }
  }

  function apply(nextMode, announce) {
    mode = VALID[nextMode] ? nextMode : 'light';
    var theme = resolved(mode);
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = theme === 'dark' ? '#14100E' : '#FAFAF8';
    if (mode === 'system') {
      try { localStorage.removeItem(KEY); } catch (e) { /* 忽略 */ }
    } else {
      try { localStorage.setItem(KEY, mode); } catch (e) { /* 忽略 */ }
    }
    if (announce) {
      document.dispatchEvent(new CustomEvent('piq:theme', {
        detail: { mode: mode, theme: theme }
      }));
    }
  }

  function setMode(nextMode) {
    apply(nextMode, true);
    syncSystemBars();
  }

  if (media) {
    var onChange = function () {
      if (mode !== 'system') return;
      apply('system', true);
      syncSystemBars();
    };
    if (typeof media.addEventListener === 'function') media.addEventListener('change', onChange);
    else if (typeof media.addListener === 'function') media.addListener(onChange);
  }

  apply(mode, false);

  window.PIQ = window.PIQ || {};
  window.PIQ.theme = {
    getMode: function () { return mode; },
    getTheme: function () { return resolved(mode); },
    setMode: setMode,
    syncSystemBars: syncSystemBars
  };
})();
