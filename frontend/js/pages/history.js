(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var art = window.PIQ.art;
  var copy = window.PIQ.copy;
  var charts = window.PIQ.charts;
  var h = ui.h;

  function sortCycles(list) {
    return (Array.isArray(list) ? list.slice() : []).sort(function (a, b) {
      var start = String(b.period_start || '').localeCompare(String(a.period_start || ''));
      if (start !== 0) return start;
      return Number(b.id || 0) - Number(a.id || 0);
    });
  }

  function finite(value) {
    if (value === null || value === undefined || value === '') return null;
    var number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function numberText(value) {
    var number = finite(value);
    if (number === null) return '—';
    var rounded = Math.round(number * 10) / 10;
    return Number.isInteger(rounded) ? String(rounded) : String(rounded);
  }

  function dateLabel(iso) {
    var date = ui.parseISO(iso);
    return date ? (date.getMonth() + 1) + '/' + date.getDate() : '';
  }

  function cycleField(cycle) {
    return cycle && cycle.period_days !== null && cycle.period_days !== undefined
      ? '经期 ' + numberText(cycle.period_days) + ' 天'
      : (cycle && cycle.period_end ? '经期 —' : '经期 进行中');
  }

  function lengthField(cycle) {
    return cycle && cycle.cycle_length !== null && cycle.cycle_length !== undefined
      ? numberText(cycle.cycle_length) + ' 天'
      : '—';
  }

  function errorText(err, fallback) {
    if (err && err.status === 503) return '算法服务暂不可用，请稍后重试';
    return ui.safeMessage(err, fallback);
  }

  function render(container, ctx) {
    var root = h('div', { class: 'page data-page history-page' });
    container.appendChild(root);
    var state = {
      alive: true,
      loading: false,
      loaded: false,
      refreshing: false,
      cycles: null,
      stats: null,
      error: null,
      timer: null
    };
    var page = {};

    function clearTimer() {
      if (state.timer) { clearTimeout(state.timer); state.timer = null; }
    }

    function skeleton() {
      return h('div', { class: 'skeleton-stack' }, [
        h('div', { class: 'skeleton skeleton-line' }),
        h('div', { class: 'skeleton' }),
        h('div', { class: 'skeleton' }),
        h('div', { class: 'skeleton' })
      ]);
    }

    function description(cycles) {
      var earliest = cycles.length
        ? cycles.slice().sort(function (a, b) { return String(a.period_start).localeCompare(String(b.period_start)); })[0]
        : null;
      var start = earliest ? ui.parseISO(earliest.period_start) : null;
      var text = cycles.length && start
        ? cycles.length + ' 次记录 · 始于 ' + start.getFullYear() + ' 年 ' + (start.getMonth() + 1) + ' 月'
        : (cycles.length ? cycles.length + ' 次记录' : '');
      return text ? h('p', { class: 'data-desc', text: text }) : null;
    }

    function chartCard(cycles) {
      var valid = cycles.slice().sort(function (a, b) {
        return String(a.period_start).localeCompare(String(b.period_start));
      }).filter(function (cycle) {
        return finite(cycle.cycle_length) !== null;
      });
      if (valid.length < 2) return null;
      var average = state.stats ? finite(state.stats.avg_cycle_length) : null;
      var points = valid.map(function (cycle) {
        return {
          label: dateLabel(cycle.period_start),
          value: Number(cycle.cycle_length),
          title: ui.fullDate(cycle.period_start) + ' · 周期 ' + numberText(cycle.cycle_length) + ' 天'
        };
      });
      return h('section', { class: 'page-section' }, [
        h('div', { class: 'card chart-card' }, [
          h('div', { class: 'chart-head' }, [
            h('h2', { class: 'section-title', text: '周期长度走势' }),
            h('span', { class: 'chart-meta', text: '最近 ' + points.length + ' 个完整周期' })
          ]),
          charts.line({
            points: points,
            average: average,
            unit: '天',
            ariaLabel: '周期长度走势图，' + points.length + ' 个完整周期',
            emptyText: '数据不足，继续记录后更新'
          }),
          h('p', { class: 'chart-note', text: average === null ? '按记录时间从早到晚' : '按记录时间从早到晚；虚线为平均值' })
        ])
      ]);
    }

    function row(cycle) {
      var date = ui.parseISO(cycle.period_start);
      var note = cycle.note ? String(cycle.note) : '';
      var second = note || (!cycle.period_end ? '结束日未填' : '');
      var aria = ui.fullDate(cycle.period_start) + '，' + cycleField(cycle) + '，周期长度 ' + lengthField(cycle);
      return h('button', {
        class: 'history-row',
        type: 'button',
        'aria-label': aria,
        onclick: function () { openEdit(cycle); }
      }, [
        h('span', { class: 'history-date' }, [
          h('span', { class: 'history-day', text: String(date.getDate()) }),
          h('span', { class: 'history-weekday', text: ui.weekday(cycle.period_start) })
        ]),
        h('span', { class: 'history-main' }, [
          h('span', { class: 'history-period', text: cycleField(cycle) }),
          second ? h('span', { class: 'history-note', text: second }) : null
        ]),
        h('span', { class: 'history-right' }, [
          h('span', { class: 'history-length-cap', text: '周期长度' }),
          h('span', { class: 'history-length-value', text: lengthField(cycle) }),
          cycle.is_anomalous ? h('span', { class: 'badge badge-danger history-anomaly', text: '异常' }) : null
        ])
      ]);
    }

    function list(cycles) {
      var groups = [];
      var byKey = {};
      cycles.forEach(function (cycle) {
        var key = String(cycle.period_start || '').slice(0, 7);
        if (!byKey[key]) {
          byKey[key] = { key: key, cycles: [] };
          groups.push(byKey[key]);
        }
        byKey[key].cycles.push(cycle);
      });
      var sections = groups.map(function (group) {
        var year = Number(group.key.slice(0, 4));
        var month = Number(group.key.slice(5, 7)) - 1;
        return h('section', { class: 'history-group' }, [
          h('div', { class: 'history-group-head' }, [
            h('h2', { class: 'history-group-title', text: ui.monthTitle(year, month) }),
            h('span', { class: 'history-group-count', text: group.cycles.length + ' 条' })
          ]),
          h('div', { class: 'card history-list' }, group.cycles.map(row))
        ]);
      });
      return h('section', { class: 'page-section history-sections' }, sections);
    }

    function emptyState() {
      return h('div', { class: 'card empty' }, [
        h('img', {
          class: 'empty-art',
          src: art.emptyPlanetBlue,
          alt: '',
          'aria-hidden': 'true',
          width: '160',
          height: '160'
        }),
        h('div', { class: 'empty-title', text: copy.emptyTitle }),
        h('div', { class: 'empty-text', text: '记录第一次经期后，这里会出现按月的周期足迹。' }),
        h('button', {
          class: 'btn btn-primary btn-block',
          type: 'button',
          onclick: function () {
            window.PIQ.pages.record.open({ mode: 'start', onDone: function () { ctx.refresh(); } });
          }
        }, copy.recordStart)
      ]);
    }

    function paint() {
      if (!state.alive) return;
      root.textContent = '';
      if (state.loading && !state.loaded) {
        root.appendChild(skeleton());
        return;
      }
      if (state.error && !state.loaded) {
        root.appendChild(ui.errorCard(ui.safeMessage(state.error, copy.genericError), '重试', function () { load(); }));
        return;
      }
      var cycles = state.cycles || [];
      if (!cycles.length) {
        root.appendChild(emptyState());
        return;
      }
      root.appendChild(description(cycles));
      var chart = chartCard(cycles);
      if (chart) root.appendChild(chart);
      root.appendChild(list(cycles));
    }

    function openEdit(cycle) {
      var error = h('div', { class: 'field-error', role: 'alert' });
      error.hidden = true;
      var busy = false;
      var startInput = h('input', {
        class: 'field-input',
        type: 'date',
        value: cycle.period_start || '',
        max: ui.todayISO(),
        'aria-label': '开始日期'
      });
      var endInput = h('input', {
        class: 'field-input',
        type: 'date',
        value: cycle.period_end || '',
        min: cycle.period_start || '',
        max: ui.todayISO(),
        'aria-label': '结束日期'
      });
      startInput.addEventListener('change', function () {
        endInput.min = startInput.value || '';
      });
      var noteInput = h('textarea', {
        class: 'field-input field-textarea',
        maxlength: '2000',
        'aria-label': '备注',
        placeholder: '添加备注'
      });
      noteInput.value = cycle.note || '';
      var save = h('button', { class: 'btn btn-primary btn-block', type: 'button' }, '保存修改');
      var remove = h('button', { class: 'btn btn-danger btn-block', type: 'button' }, '删除这条记录');

      function fail(message) {
        error.textContent = message;
        error.hidden = false;
        try { error.scrollIntoView({ block: 'nearest' }); } catch (e) { /* 忽略 */ }
      }

      function setBusy(value, saveLabel, removeLabel) {
        busy = value;
        save.disabled = value;
        remove.disabled = value;
        save.textContent = saveLabel || '保存修改';
        remove.textContent = removeLabel || '删除这条记录';
      }

      var content = h('div', { class: 'edit-form' }, [
        h('label', { class: 'field' }, [
          h('span', { class: 'field-label', text: '开始日期' }),
          startInput
        ]),
        h('label', { class: 'field' }, [
          h('span', { class: 'field-label', text: '结束日期' }),
          endInput,
          h('span', { class: 'field-hint', text: '留空表示尚未结束' })
        ]),
        h('label', { class: 'field' }, [
          h('span', { class: 'field-label', text: '备注' }),
          noteInput
        ]),
        error,
        save,
        h('div', { class: 'edit-danger-zone' }, [remove])
      ]);
      ui.openSheet({
        title: '编辑记录',
        subtitle: ui.fullDate(cycle.period_start) + ' · ' + ui.weekday(cycle.period_start),
        content: content
      });

      function refreshPage() {
        ui.dismissAll();
        if (state.alive) ctx.refresh();
      }

      save.addEventListener('click', async function () {
        if (busy) return;
        error.hidden = true;
        var start = startInput.value;
        var end = endInput.value;
        var note = noteInput.value;
        if (!start) { fail('请选择开始日期'); return; }
        if (!ui.isValidISO(start)) { fail('日期格式不正确'); return; }
        if (end && !ui.isValidISO(end)) { fail('日期格式不正确'); return; }
        if (end && end < start) { fail('结束日期不能早于开始日期'); return; }
        if (start > ui.todayISO() || (end && end > ui.todayISO())) { fail('日期不能晚于今天'); return; }
        var body = {};
        if (start !== cycle.period_start) body.period_start = start;
        if (end && end !== (cycle.period_end || '')) body.period_end = end;
        if (!end && cycle.period_end) body.clear_period_end = true;
        if (note !== (cycle.note || '')) body.note = note;
        if (!Object.keys(body).length) { fail('没有需要保存的修改'); return; }
        setBusy(true, '保存中…');
        try {
          await api.updateCycle(cycle.id, body);
          if (!state.alive) return;
          ui.toast('已保存');
          refreshPage();
        } catch (err) {
          if (!state.alive) return;
          setBusy(false);
          fail(errorText(err, '保存失败，请稍后重试'));
        }
      });

      remove.addEventListener('click', async function () {
        if (busy) return;
        error.hidden = true;
        var confirmed = await ui.openDialog({
          danger: true,
          title: '删除记录',
          text: '删除后会重新计算模型与预测，且不可撤销。',
          confirmLabel: '删除'
        });
        if (!confirmed || busy) return;
        setBusy(true, '保存修改', '删除中…');
        try {
          await api.deleteCycle(cycle.id);
          if (!state.alive) return;
          ui.toast('已删除');
          refreshPage();
        } catch (err) {
          if (!state.alive) return;
          setBusy(false);
          fail(errorText(err, '删除失败，请稍后重试'));
        }
      });
    }

    async function load() {
      if (state.refreshing) return;
      state.refreshing = true;
      state.loading = true;
      state.error = null;
      if (!state.loaded) {
        state.timer = setTimeout(function () {
          if (state.alive && state.loading) paint();
        }, 400);
      }
      var results = await Promise.allSettled([api.getCycles(), api.cycleStats()]);
      if (!state.alive) return;
      state.refreshing = false;
      state.loading = false;
      clearTimer();
      var cycles = results[0];
      if (cycles.status !== 'fulfilled' || !Array.isArray(cycles.value)) {
        state.error = cycles.reason || { detail: copy.genericError };
        state.cycles = null;
        state.loaded = false;
        paint();
        return;
      }
      state.cycles = sortCycles(cycles.value);
      state.stats = results[1].status === 'fulfilled' ? results[1].value : null;
      state.loaded = true;
      paint();
    }

    page.refresh = function () { return load(); };
    page.destroy = function () { state.alive = false; clearTimer(); };
    load();
    return page;
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.pages = window.PIQ.pages || {};
  window.PIQ.pages.history = { render: render };
})();
