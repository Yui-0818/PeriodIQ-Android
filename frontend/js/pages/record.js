(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var copy = window.PIQ.copy;
  var h = ui.h;

  function sortCycles(list) {
    return (Array.isArray(list) ? list.slice() : []).sort(function (a, b) {
      return String(b.period_start || '').localeCompare(String(a.period_start || ''));
    });
  }

  function open(options) {
    options = options || {};
    var today = ui.todayISO();
    var cycles = null;
    var busy = false;
    var finished = false;
    var error = h('div', { class: 'field-error', role: 'alert' });
    error.hidden = true;
    var body = h('div', { class: 'record-form' });

    var modal = ui.openSheet({
      title: '记录',
      subtitle: '今天 · ' + ui.monthDay(today),
      content: [body, error],
      onClose: function () { modal.cyclesClosed = true; }
    });
    modal.cyclesClosed = false;

    function clearError() {
      error.hidden = true;
      error.textContent = '';
    }
    function fail(message) {
      error.textContent = message;
      error.hidden = false;
      try { error.scrollIntoView({ block: 'nearest' }); } catch (e) { /* 忽略 */ }
    }
    function success(message) {
      if (finished) return;
      finished = true;
      ui.toast(message);
      ui.dismissAll();
      if (typeof options.onDone === 'function') options.onDone();
    }

    function busyButton(button, label, work) {
      if (busy) return;
      busy = true;
      clearError();
      var original = button.textContent;
      button.disabled = true;
      button.textContent = label;
      Promise.resolve()
        .then(work)
        .catch(function (err) { fail(ui.safeMessage(err, copy.recordError)); })
        .then(function () {
          busy = false;
          button.disabled = false;
          button.textContent = original;
        });
    }

    function actionBlock(title, note, label, primary, disabled, onClick) {
      return h('div', { class: 'record-action' }, [
        h('div', { class: 'record-action-title', text: title }),
        h('div', { class: 'record-action-note', text: note }),
        h('button', {
          class: 'btn btn-block' + (primary ? ' btn-primary' : ''),
          type: 'button',
          disabled: disabled,
          onclick: onClick
        }, label)
      ]);
    }

    function start(button) {
      busyButton(button, '记录中…', async function () {
        await api.createCycle(today);
        success('已记录经期开始');
      });
    }

    function end(button, cycle) {
      busyButton(button, '记录中…', async function () {
        await api.updateCycle(cycle.id, { period_end: today });
        success('已记录经期结束');
      });
    }

    function renderActions() {
      body.textContent = '';
      var latest = cycles && cycles.length ? cycles[0] : null;
      var openCycle = cycles ? (cycles.filter(function (c) { return !c.period_end; })[0] || null) : null;
      var endedToday = Boolean(latest && latest.period_end === today);
      var startDisabled = Boolean(openCycle) || Boolean(latest && latest.period_start === today) || endedToday;
      var startNote = copy.recordStartNote;
      if (openCycle) startNote = '当前经期还未结束，请先记录结束日。';
      else if (endedToday) startNote = '今天已有记录。';

      body.appendChild(actionBlock(copy.recordStart, startNote, copy.recordStart, true, startDisabled, function () {
        start(this);
      }));

      if (openCycle) {
        body.appendChild(actionBlock(
          copy.recordEnd,
          copy.recordEndNote + '（开始于 ' + ui.monthDay(openCycle.period_start) + '）',
          copy.recordEnd,
          false,
          false,
          function () { end(this, openCycle); }
        ));
      }

      if (latest) {
        body.appendChild(actionBlock(
          copy.recordEdit,
          copy.recordEditNote,
          copy.recordEdit,
          false,
          false,
          function () { renderEdit(latest); }
        ));
      }
    }

    function renderEdit(cycle) {
      body.textContent = '';
      clearError();
      modal.setTitle('修正记录', '最近一条 · ' + ui.monthDay(cycle.period_start) + (cycle.period_end ? ' – ' + ui.monthDay(cycle.period_end) : ' 未结束'));

      var startInput = h('input', {
        class: 'field-input',
        type: 'date',
        value: cycle.period_start || '',
        max: today,
        'aria-label': '开始日期'
      });
      var endInput = h('input', {
        class: 'field-input',
        type: 'date',
        value: cycle.period_end || '',
        min: cycle.period_start || '',
        max: today,
        'aria-label': '结束日期'
      });
      startInput.addEventListener('change', function () {
        endInput.min = startInput.value || '';
      });
      var save = h('button', { class: 'btn btn-primary btn-block', type: 'button' }, copy.saveEdit);
      save.addEventListener('click', function () {
        if (busy) return;
        clearError();
        var start = startInput.value;
        var end = endInput.value;
        if (!start) { fail('请选择开始日期'); return; }
        if (!ui.isValidISO(start) || !ui.isValidISO(end ? end : start)) { fail('日期格式不正确'); return; }
        if (end && end < start) { fail('结束日期不能早于开始日期'); return; }
        if (start > today || (end && end > today)) { fail('日期不能晚于今天'); return; }
        var payload = {};
        if (start !== cycle.period_start) payload.period_start = start;
        if (end && end !== cycle.period_end) payload.period_end = end;
        if (!end && cycle.period_end) payload.clear_period_end = true;
        if (!Object.keys(payload).length) { fail('没有需要保存的修改'); return; }
        busyButton(save, '保存中…', async function () {
          await api.updateCycle(cycle.id, payload);
          success('已保存');
        });
      });

      body.appendChild(h('label', { class: 'field' }, [
        h('span', { class: 'field-label', text: '开始日期' }),
        startInput
      ]));
      body.appendChild(h('label', { class: 'field' }, [
        h('span', { class: 'field-label', text: '结束日期' }),
        endInput,
        h('span', { class: 'field-hint', text: '结束日期可留空，表示经期尚未结束。' })
      ]));
      body.appendChild(save);
      body.appendChild(h('button', {
        class: 'btn btn-block',
        type: 'button',
        onclick: function () {
          modal.setTitle('记录', '今天 · ' + ui.monthDay(today));
          renderActions();
        }
      }, '返回'));
    }

    function loadCycles() {
      api.getCycles().then(function (list) {
        cycles = sortCycles(list);
        if (modal.closed || modal.cyclesClosed) return;
        if (options.mode === 'edit' && cycles.length) {
          renderEdit(options.cycle || cycles[0]);
        } else {
          renderActions();
        }
      }).catch(function (err) {
        if (modal.closed || modal.cyclesClosed) return;
        cycles = [];
        renderActions();
        fail(ui.safeMessage(err, '暂时无法读取最近记录，保存时如有冲突会提示。'));
      });
    }

    if (options.mode === 'edit' && options.cycle) {
      renderEdit(options.cycle);
      loadCycles = function () { /* 已直接进入编辑 */ };
    } else {
      body.appendChild(ui.skeleton('line'));
      loadCycles();
    }
    return modal;
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.pages = window.PIQ.pages || {};
  window.PIQ.pages.record = { open: open, sortCycles: sortCycles };
})();
