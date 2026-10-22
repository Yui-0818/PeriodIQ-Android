(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';
  var FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
  var ICONS = {
    today: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4',
    calendar: 'M4 6h16v14H4zM8 3v4M16 3v4M4 10h16',
    history: 'M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4M12 8v4l3 2',
    analysis: 'M4 20V11M10 20V4M16 20v-6M22 20H2'
  };
  var modalStack = [];
  var pendingBacks = 0;
  var modalSeq = 0;
  var toastTimer = null;

  function append(el, child) {
    if (child === null || child === undefined || child === false) return;
    if (Array.isArray(child)) {
      child.forEach(function (item) { append(el, item); });
      return;
    }
    if (child.nodeType) { el.appendChild(child); return; }
    el.appendChild(document.createTextNode(String(child)));
  }

  function h(tag, attrs, children) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'class') el.className = value;
        else if (key === 'text') el.textContent = value;
        else if (key === 'dataset') Object.assign(el.dataset, value);
        else if (key.slice(0, 2) === 'on' && typeof value === 'function') {
          el.addEventListener(key.slice(2).toLowerCase(), value);
        } else if (value === true) el.setAttribute(key, '');
        else el.setAttribute(key, value);
      });
    }
    append(el, children);
    return el;
  }

  function icon(name) {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '1.8');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    svg.setAttribute('aria-hidden', 'true');
    var path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', ICONS[name] || ICONS.today);
    svg.appendChild(path);
    return svg;
  }

  function pad2(value) { return String(value).padStart(2, '0'); }

  function parseISO(iso) {
    if (!iso) return null;
    var parts = String(iso).slice(0, 10).split('-');
    if (parts.length !== 3) return null;
    var year = Number(parts[0]);
    var month = Number(parts[1]);
    var day = Number(parts[2]);
    if (!year || !month || !day) return null;
    var date = new Date(year, month - 1, day);
    return Number.isNaN(date.getTime()) ? null : date;
  }
  function isoOf(date) {
    return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
  }
  function todayISO() { return isoOf(new Date()); }
  function addDays(iso, days) {
    var date = parseISO(iso);
    if (!date) return iso;
    date.setDate(date.getDate() + days);
    return isoOf(date);
  }
  function diffDays(fromISO, toISO) {
    var from = parseISO(fromISO);
    var to = parseISO(toISO);
    if (!from || !to) return NaN;
    var a = Date.UTC(from.getFullYear(), from.getMonth(), from.getDate());
    var b = Date.UTC(to.getFullYear(), to.getMonth(), to.getDate());
    return Math.round((b - a) / 86400000);
  }
  function monthDay(iso) {
    var date = parseISO(iso);
    return date ? (date.getMonth() + 1) + ' 月 ' + date.getDate() + ' 日' : '—';
  }
  function fullDate(iso) {
    var date = parseISO(iso);
    return date ? date.getFullYear() + ' 年 ' + (date.getMonth() + 1) + ' 月 ' + date.getDate() + ' 日' : '—';
  }
  function weekday(iso) {
    var date = parseISO(iso);
    return date ? ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getDay()] : '';
  }
  function monthTitle(year, monthIndex) { return year + ' 年 ' + (monthIndex + 1) + ' 月'; }
  function inRange(iso, start, end) {
    if (!iso || !start) return false;
    return iso >= start && iso <= (end || start);
  }
  function isValidISO(value) { return /^\d{4}-\d{2}-\d{2}$/.test(String(value || '')); }

  function safeMessage(err, fallback) {
    var detail = err && err.detail ? String(err.detail) : '';
    if (!detail || detail.length > 80) return fallback;
    if (/Exception|java\.|\/api\/|\/data\/|\/storage\/|\.py\b|\.kt\b/.test(detail)) return fallback;
    return detail;
  }

  function errorCard(message, retry, onRetry) {
    return h('div', { class: 'error-card', role: 'alert' }, [
      h('div', { class: 'error-text', text: message }),
      onRetry ? h('button', { class: 'btn', type: 'button', onclick: onRetry }, retry || '重试') : null
    ]);
  }

  function toast(message) {
    var root = document.getElementById('toast-root');
    if (!root) return;
    root.textContent = '';
    var node = h('div', { class: 'toast' }, message);
    var app = document.getElementById('app');
    if (app && app.classList.contains('subpage')) node.classList.add('is-subpage');
    root.appendChild(node);
    requestAnimationFrame(function () { node.classList.add('is-open'); });
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      node.classList.remove('is-open');
      setTimeout(function () { if (node.parentNode) node.parentNode.removeChild(node); }, 220);
    }, 2000);
  }

  function focusInitial(panel) {
    var nodes = panel.querySelectorAll(FOCUSABLE);
    var target = panel.querySelector('[data-autofocus]') || nodes[0] || panel;
    try { target.focus({ preventScroll: true }); } catch (e) { target.focus(); }
  }

  function closeModal(modal, fromHistory) {
    if (!modal || modal.closed) return;
    modal.closed = true;
    var index = modalStack.indexOf(modal);
    if (index >= 0) modalStack.splice(index, 1);
    if (modal.pushed && !fromHistory) {
      pendingBacks += 1;
      try { history.back(); } catch (e) { pendingBacks = Math.max(0, pendingBacks - 1); }
    }
    modal.scrim.classList.remove('is-open');
    modal.panel.classList.remove('is-open');
    if (!modalStack.length) {
      var app = document.getElementById('app');
      if (app) app.classList.remove('app-dim');
    }
    setTimeout(function () {
      if (modal.scrim.parentNode) modal.scrim.parentNode.removeChild(modal.scrim);
      if (modal.panel.parentNode) modal.panel.parentNode.removeChild(modal.panel);
    }, 300);
    if (modal.previousFocus && document.contains(modal.previousFocus)) {
      try { modal.previousFocus.focus({ preventScroll: true }); } catch (e) { /* 已不可聚焦 */ }
    }
    if (typeof modal.onClose === 'function') modal.onClose();
  }

  function openModal(options) {
    var kind = options.kind === 'dialog' ? 'dialog' : 'sheet';
    var root = document.getElementById('sheet-root');
    if (!root) return { close: function () {} };
    var seq = ++modalSeq;
    var titleId = 'piq-modal-title-' + seq;
    var previousFocus = document.activeElement;
    var scrim = h('div', { class: 'modal-scrim' + (kind === 'dialog' ? ' is-dialog' : ''), 'aria-hidden': 'true' });
    var panel = h(kind === 'dialog' ? 'div' : 'section', {
      class: kind === 'dialog' ? 'dialog' : 'sheet',
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': options.title ? titleId : null,
      'aria-label': options.title ? null : (options.label || '对话框'),
      tabindex: '-1'
    });
    var body = h('div', { class: kind === 'dialog' ? 'dialog-body' : 'sheet-body' });
    var head = null;
    var titleNode = null;

    if (kind === 'sheet') {
      var handle = h('div', { class: 'sheet-handle' });
      var closeBtn = h('button', {
        class: 'sheet-close',
        type: 'button',
        'aria-label': '关闭',
        onclick: function () { closeModal(modal, false); }
      }, '×');
      titleNode = h('h2', { class: 'sheet-title', id: titleId, text: options.title || '' });
      head = h('div', { class: 'sheet-head' }, [
        h('div', { class: 'sheet-head-text' }, [
          titleNode,
          options.subtitle ? h('div', { class: 'sheet-sub', text: options.subtitle }) : null
        ]),
        closeBtn
      ]);
      panel.appendChild(handle);
      panel.appendChild(head);
    } else {
      titleNode = h('h2', { class: 'dialog-title', id: titleId, text: options.title || '' });
      panel.appendChild(titleNode);
    }
    panel.appendChild(body);
    append(body, options.content);

    var modal = {
      panel: panel,
      scrim: scrim,
      body: body,
      titleNode: titleNode,
      previousFocus: previousFocus,
      dismissible: options.dismissible !== false,
      onClose: options.onClose,
      closed: false,
      pushed: false,
      close: function () { closeModal(modal, false); },
      setContent: function (node) {
        body.textContent = '';
        append(body, node);
      },
      setTitle: function (title, subtitle) {
        if (titleNode) titleNode.textContent = title;
        if (subtitle !== undefined) {
          var sub = head && head.querySelector('.sheet-sub');
          if (sub) sub.textContent = subtitle;
        }
      }
    };

    scrim.addEventListener('click', function () {
      if (modal.dismissible) closeModal(modal, false);
    });
    if (options.dismissible !== false && kind === 'sheet') {
      var startY = null;
      var startSwipe = function (event) {
        if (event.touches.length === 1) startY = event.touches[0].clientY;
      };
      var endSwipe = function (event) {
        if (startY === null) return;
        var delta = event.changedTouches[0].clientY - startY;
        startY = null;
        if (delta > 60) closeModal(modal, false);
      };
      [head, panel.querySelector('.sheet-handle')].forEach(function (zone) {
        if (!zone) return;
        zone.addEventListener('touchstart', startSwipe, { passive: true });
        zone.addEventListener('touchend', endSwipe, { passive: true });
      });
    }

    root.appendChild(scrim);
    root.appendChild(panel);
    modalStack.push(modal);
    var app = document.getElementById('app');
    if (app) app.classList.add('app-dim');
    try {
      history.pushState({ piqModal: true }, '', location.hash);
      modal.pushed = true;
    } catch (e) { /* 历史不可写时仅靠返回键兜底 */ }
    requestAnimationFrame(function () {
      scrim.classList.add('is-open');
      panel.classList.add('is-open');
      focusInitial(panel);
    });
    return modal;
  }

  function openSheet(options) {
    return openModal({
      kind: 'sheet',
      title: options.title,
      subtitle: options.subtitle,
      content: options.content,
      dismissible: options.dismissible,
      onClose: options.onClose
    });
  }

  function openDialog(options) {
    return new Promise(function (resolve) {
      var settled = false;
      var settle = function (value) {
        if (settled) return;
        settled = true;
        modal.close();
        resolve(value);
      };
      var content = h('div', { class: 'dialog-body' }, [
        options.text ? h('div', { class: 'dialog-text', text: options.text }) : null,
        h('div', { class: 'dialog-actions' }, [
          h('button', { class: 'btn', type: 'button', onclick: function () { settle(false); } }, options.cancelLabel || '取消'),
          h('button', {
            class: 'btn ' + (options.danger ? 'btn-danger' : 'btn-primary'),
            type: 'button',
            onclick: function () { settle(true); }
          }, options.confirmLabel || '确定')
        ])
      ]);
      var modal = openModal({
        kind: 'dialog',
        title: options.title,
        content: content,
        dismissible: true,
        onClose: function () {
          if (!settled) { settled = true; resolve(false); }
        }
      });
    });
  }

  function closeAll() {
    while (modalStack.length) closeModal(modalStack[modalStack.length - 1], true);
  }

  function dismissAll() {
    var pushed = modalStack.filter(function (modal) { return modal.pushed; }).length;
    while (modalStack.length) closeModal(modalStack[modalStack.length - 1], true);
    if (pushed) {
      pendingBacks += 1;
      try { history.go(-pushed); } catch (e) { pendingBacks = Math.max(0, pendingBacks - 1); }
    }
  }

  function handleBack() {
    var top = modalStack[modalStack.length - 1];
    if (!top) return false;
    closeModal(top, false);
    return true;
  }

  document.addEventListener('keydown', function (event) {
    var top = modalStack[modalStack.length - 1];
    if (!top) return;
    if (event.key === 'Escape') {
      if (top.dismissible) {
        event.preventDefault();
        closeModal(top, false);
      }
      return;
    }
    if (event.key !== 'Tab') return;
    var nodes = Array.prototype.filter.call(top.panel.querySelectorAll(FOCUSABLE), function (node) {
      return node.offsetParent !== null;
    });
    if (!nodes.length) {
      event.preventDefault();
      top.panel.focus();
      return;
    }
    var first = nodes[0];
    var last = nodes[nodes.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });

  window.addEventListener('popstate', function () {
    if (pendingBacks > 0) {
      pendingBacks -= 1;
      return;
    }
    var top = modalStack[modalStack.length - 1];
    if (top) closeModal(top, true);
  });

  function skeleton(kind) {
    var className = 'skeleton';
    if (kind) className += ' skeleton-' + kind;
    return h('div', { class: className, 'aria-hidden': 'true' });
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.ui = {
    h: h,
    icon: icon,
    append: append,
    toast: toast,
    openSheet: openSheet,
    openDialog: openDialog,
    closeAll: closeAll,
    dismissAll: dismissAll,
    handleBack: handleBack,
    errorCard: errorCard,
    safeMessage: safeMessage,
    skeleton: skeleton,
    pad2: pad2,
    parseISO: parseISO,
    isoOf: isoOf,
    todayISO: todayISO,
    addDays: addDays,
    diffDays: diffDays,
    monthDay: monthDay,
    fullDate: fullDate,
    weekday: weekday,
    monthTitle: monthTitle,
    inRange: inRange,
    isValidISO: isValidISO
  };
})();
