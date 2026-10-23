(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var art = window.PIQ.art;
  var copy = window.PIQ.copy;
  var h = ui.h;
  var WEEK = ['一', '二', '三', '四', '五', '六', '日'];
  var MONTH_NAMES = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月'];
  var PHASE_LABEL = { period: '经期', predicted: '预测经期', fertile: '易孕期', ovulation: '排卵日' };

  function sortCycles(list) {
    return (Array.isArray(list) ? list.slice() : []).sort(function (a, b) {
      return String(b.period_start || '').localeCompare(String(a.period_start || ''));
    });
  }

  function buildPhaseIndex(cycles, fertile, pred) {
    var index = {};
    cycles.forEach(function (cycle) {
      if (!cycle.period_start) return;
      var end = cycle.period_end || cycle.period_start;
      var cursor = cycle.period_start;
      var guard = 0;
      while (cursor <= end && guard < 90) {
        if (!index[cursor]) index[cursor] = 'period';
        cursor = ui.addDays(cursor, 1);
        guard += 1;
      }
    });
    var fill = function (start, end, phase) {
      if (!start || !end) return;
      var cursor = start;
      var guard = 0;
      while (cursor <= end && guard < 400) {
        if (!index[cursor]) index[cursor] = phase;
        cursor = ui.addDays(cursor, 1);
        guard += 1;
      }
    };
    var windows = fertile && Array.isArray(fertile.predicted_period_windows) ? fertile.predicted_period_windows : [];
    if (windows.length) {
      windows.forEach(function (window) { fill(window.start, window.end, 'predicted'); });
    } else if (pred && pred.predicted_start_lower && pred.predicted_start_upper) {
      fill(pred.predicted_start_lower, pred.predicted_start_upper, 'predicted');
    }
    if (fertile && fertile.ovulation_date && !index[fertile.ovulation_date]) {
      index[fertile.ovulation_date] = 'ovulation';
    }
    if (fertile && fertile.fertile_window) {
      fill(fertile.fertile_window.start, fertile.fertile_window.end, 'fertile');
    }
    return index;
  }

  function legendItem(cls, label) {
    return h('span', { class: 'legend-item' }, [
      h('span', { class: 'cal-key ' + cls, 'aria-hidden': 'true' }),
      label
    ]);
  }

  function render(container, ctx) {
    var today = ui.todayISO();
    var todayDate = ui.parseISO(today);
    var state = {
      alive: true,
      loading: false,
      loaded: false,
      error: null,
      cycles: null,
      fertile: null,
      pred: null,
      year: todayDate.getFullYear(),
      month: todayDate.getMonth(),
      refreshing: false,
      skeleton: false,
      timer: null
    };
    if (ctx && ctx.route && ctx.route.params && ctx.route.params.date) {
      var parsed = ui.parseISO(ctx.route.params.date);
      if (parsed) {
        state.year = parsed.getFullYear();
        state.month = parsed.getMonth();
      }
    }
    var root = h('div', { class: 'page calendar-page' });
    container.appendChild(root);
    var page = {};
    var touchX = null;
    var touchY = null;

    function onTouchStart(event) {
      if (event.touches.length !== 1) return;
      touchX = event.touches[0].clientX;
      touchY = event.touches[0].clientY;
    }

    function onTouchEnd(event) {
      if (touchX === null) return;
      var dx = event.changedTouches[0].clientX - touchX;
      var dy = event.changedTouches[0].clientY - touchY;
      touchX = null;
      touchY = null;
      if (Math.abs(dx) > 52 && Math.abs(dy) < 40) monthShift(dx < 0 ? 1 : -1);
    }

    root.addEventListener('touchstart', onTouchStart, { passive: true });
    root.addEventListener('touchend', onTouchEnd, { passive: true });

    function clearTimer() {
      if (state.timer) { clearTimeout(state.timer); state.timer = null; }
    }

    function monthShift(delta) {
      var date = new Date(state.year, state.month + delta, 1);
      state.year = date.getFullYear();
      state.month = date.getMonth();
      paint();
    }

    function phaseIndex() {
      return buildPhaseIndex(state.cycles || [], state.fertile, state.pred);
    }

    function cycleAt(date) {
      return (state.cycles || []).filter(function (cycle) {
        return ui.inRange(date, cycle.period_start, cycle.period_end || cycle.period_start);
      })[0] || null;
    }

    function cellLabel(date, phase, cycle) {
      if (cycle) return ui.fullDate(date) + '，经期第 ' + (ui.diffDays(cycle.period_start, date) + 1) + ' 天';
      return ui.fullDate(date) + (phase ? '，' + PHASE_LABEL[phase] : '');
    }

    function openDay(date, phase) {
      var cycle = cycleAt(date);
      var todayISO = ui.todayISO();
      var latest = state.cycles && state.cycles.length ? state.cycles[0] : null;
      var openCycle = (state.cycles || []).filter(function (item) { return !item.period_end; })[0] || null;
      var endedToday = Boolean(latest && latest.period_end === todayISO);
      var status;
      if (cycle) status = '经期第 ' + (ui.diffDays(cycle.period_start, date) + 1) + ' 天';
      else if (openCycle && date === todayISO) status = '经期中 · 第 ' + (ui.diffDays(openCycle.period_start, date) + 1) + ' 天';
      else if (phase === 'predicted') status = '预测经期';
      else if (phase === 'ovulation') status = '易孕期 · 预计排卵日';
      else if (phase === 'fertile') status = '易孕期';
      else status = '无记录';

      var meta = h('div', { class: 'day-meta' });
      if (cycle) {
        meta.appendChild(h('div', { text: '开始 ' + ui.monthDay(cycle.period_start) + (cycle.period_end ? ' · 结束 ' + ui.monthDay(cycle.period_end) : ' · 尚未结束') }));
        if (cycle.period_days) meta.appendChild(h('div', { text: '经期 ' + cycle.period_days + ' 天' }));
      } else if (openCycle && date === todayISO) {
        meta.appendChild(h('div', { text: '开始 ' + ui.monthDay(openCycle.period_start) + ' · 尚未结束' }));
      } else if (phase === 'predicted') {
        var lower = state.pred && state.pred.predicted_start_lower;
        var upper = state.pred && state.pred.predicted_start_upper;
        if (lower && upper) meta.appendChild(h('div', { text: '预计区间 ' + ui.monthDay(lower) + ' – ' + ui.monthDay(upper) }));
        else if (state.pred && state.pred.predicted_date) meta.appendChild(h('div', { text: '预计 ' + ui.monthDay(state.pred.predicted_date) }));
      } else if (phase === 'fertile' || phase === 'ovulation') {
        if (state.fertile && state.fertile.ovulation_date) {
          meta.appendChild(h('div', { text: '预计排卵日 ' + ui.monthDay(state.fertile.ovulation_date) }));
        }
        meta.appendChild(h('div', { class: 'day-note', text: copy.fertileNote }));
      } else if (date !== todayISO) {
        meta.appendChild(h('div', { class: 'day-note', text: '历史日期暂不支持补录。' }));
      }

      var actions = h('div', { class: 'day-actions' });

      if (!openCycle && !endedToday && date === todayISO) {
        actions.appendChild(h('button', {
          class: 'btn btn-primary btn-block',
          type: 'button',
          onclick: function () {
            window.PIQ.pages.record.open({
              mode: 'start',
              onDone: function () { page.refresh(); }
            });
          }
        }, copy.recordStart));
      }
      var endTarget = cycle && !cycle.period_end ? cycle : (openCycle && date === todayISO ? openCycle : null);
      if (endTarget && todayISO >= endTarget.period_start) {
        actions.appendChild(h('button', {
          class: 'btn btn-block',
          type: 'button',
          onclick: function () {
            window.PIQ.pages.record.open({
              mode: 'end',
              cycle: endTarget,
              onDone: function () { page.refresh(); }
            });
          }
        }, copy.recordEnd));
      }
      if (cycle || (date === todayISO && (openCycle || latest))) {
        actions.appendChild(h('button', {
          class: 'btn btn-block',
          type: 'button',
          onclick: function () {
            window.PIQ.pages.record.open({
              mode: 'edit',
              cycle: cycle || openCycle || latest,
              onDone: function () { page.refresh(); }
            });
          }
        }, '修正记录'));
      }
      if (!actions.childNodes.length) {
        actions.appendChild(h('button', {
          class: 'btn btn-block',
          type: 'button',
          onclick: function () { sheet.close(); }
        }, '知道了'));
      }

      var content = h('div', { class: 'day-meta' }, [meta, actions]);
      var sheet = ui.openSheet({
        title: ui.monthDay(date) + ' · ' + ui.weekday(date),
        subtitle: status,
        content: content
      });
    }

    function paintGrid(wrap) {
      var phaseIndexValue = phaseIndex();
      var first = new Date(state.year, state.month, 1);
      var start = ui.addDays(ui.isoOf(first), -((first.getDay() + 6) % 7));
      var grid = h('div', { class: 'month-grid' });
      var cycles = state.cycles || [];
      for (var i = 0; i < 42; i += 1) {
        var date = ui.addDays(start, i);
        var parsed = ui.parseISO(date);
        var outside = parsed.getMonth() !== state.month;
        var phase = phaseIndexValue[date] || '';
        var cycle = cycles.filter(function (item) {
          return ui.inRange(date, item.period_start, item.period_end || item.period_start);
        })[0] || null;
        var classes = 'cal-cell' + (outside ? ' is-outside' : '') + (phase ? ' is-' + phase : '') + (date === today ? ' is-today' : '');
        var label = cellLabel(date, phase, cycle);
        grid.appendChild(h('button', {
          class: classes,
          type: 'button',
          'aria-label': label,
          onclick: function (day, ph) {
            return function () { openDay(day, ph); };
          }(date, phase)
        }, [
          h('span', { class: 'cal-day', text: String(parsed.getDate()) }),
          phase ? h('span', { class: 'cal-mark is-' + phase, 'aria-hidden': 'true' }) : null
        ]));
      }
      wrap.appendChild(grid);
    }

    function emptyState() {
      return h('div', { class: 'empty' }, [
        h('div', { class: 'empty-art-wrap' }, [
          h('img', {
            class: 'empty-art',
            src: art.emptyMoon,
            alt: '',
            'aria-hidden': 'true',
            width: '84',
            height: '84'
          })
        ]),
        h('div', { class: 'empty-title', text: copy.emptyTitle }),
        h('div', { class: 'empty-text', text: '记录第一次经期后，日历会显示周期、易孕期与预测。' }),
        h('button', {
          class: 'btn btn-hero btn-block',
          type: 'button',
          onclick: function () {
            window.PIQ.pages.record.open({ mode: 'start', onDone: function () { page.refresh(); } });
          }
        }, copy.recordStart)
      ]);
    }

    function shortDate(iso) {
      var date = ui.parseISO(iso);
      return date ? (date.getMonth() + 1) + '/' + date.getDate() : '—';
    }

    function summaryCell(value, label) {
      return h('div', { class: 'calendar-stat' }, [
        h('span', { class: 'calendar-stat-value', text: value }),
        h('span', { class: 'calendar-stat-label', text: label })
      ]);
    }

    function summary() {
      var monthKey = state.year + '-' + ui.pad2(state.month + 1);
      var monthCycles = (state.cycles || []).filter(function (cycle) {
        return String(cycle.period_start || '').slice(0, 7) === monthKey;
      });
      var lengths = monthCycles.map(function (cycle) {
        var days = Number(cycle.period_days);
        if (Number.isFinite(days) && days > 0) return days;
        return cycle.period_start && cycle.period_end ? ui.diffDays(cycle.period_start, cycle.period_end) + 1 : null;
      }).filter(function (value) { return value !== null; });
      var average = lengths.length
        ? Math.round(lengths.reduce(function (sum, value) { return sum + value; }, 0) / lengths.length * 10) / 10
        : null;
      var next = state.pred && state.pred.predicted_date;
      return h('div', { class: 'calendar-summary' }, [
        summaryCell(String(monthCycles.length), '本月记录'),
        summaryCell(average === null ? '—' : String(average), '平均经期'),
        summaryCell(next ? shortDate(next) : '—', '下次预测')
      ]);
    }

    function paint() {
      if (state.loaded && !state.loading && (!state.cycles || !state.cycles.length)) {
        root.textContent = '';
        root.appendChild(emptyState());
        return;
      }
      root.textContent = '';
      var card = h('section', { class: 'calendar-card glass-surface', 'aria-label': '日历，左右滑动切换月份' });
      var monthBar = h('div', { class: 'month-bar' }, [
        h('button', {
          class: 'month-side',
          type: 'button',
          'aria-label': '上个月',
          onclick: function () { monthShift(-1); }
        }, '‹'),
        h('h2', {
          class: 'month-heading',
          'aria-label': ui.monthTitle(state.year, state.month)
        }, [
          h('span', { class: 'month-kicker', text: state.year + ' · 周期台账' }),
          h('span', { class: 'month-title', text: MONTH_NAMES[state.month] })
        ]),
        h('button', {
          class: 'month-side',
          type: 'button',
          'aria-label': '下个月',
          onclick: function () { monthShift(1); }
        }, '›'),
        h('button', {
          class: 'month-today',
          type: 'button',
          'aria-label': '回到今天',
          onclick: function () {
            var now = ui.parseISO(ui.todayISO());
            state.year = now.getFullYear();
            state.month = now.getMonth();
            state.selected = ui.todayISO();
            paint();
          }
        }, '今天')
      ]);
      card.appendChild(monthBar);
      card.appendChild(h('div', { class: 'week-row', 'aria-hidden': 'true' }, WEEK.map(function (day) {
        return h('span', { class: 'week-day-label', text: day });
      })));
      root.appendChild(card);

      if (state.loading && !state.loaded && state.skeleton) {
        var skeletonGrid = h('div', { class: 'month-grid' });
        for (var s = 0; s < 42; s += 1) skeletonGrid.appendChild(h('div', { class: 'skeleton cal-skeleton', 'aria-hidden': 'true' }));
        card.appendChild(skeletonGrid);
        return;
      }
      if (state.loading && !state.loaded) return;
      if (state.error && !state.loaded) {
        root.appendChild(ui.errorCard(ui.safeMessage(state.error, copy.genericError), '重试', function () { load(); }));
        return;
      }
      paintGrid(card);
      card.appendChild(h('div', { class: 'legend' }, [
        legendItem('is-period', '经期'),
        legendItem('is-predicted', '预测'),
        legendItem('is-fertile', '易孕'),
        legendItem('is-ovulation', '排卵')
      ]));
      card.appendChild(summary());
      card.appendChild(h('div', { class: 'calendar-source', text: '台账 · 本机记录' }));
      if (!state.fertile) {
        card.appendChild(h('div', { class: 'cal-hint', text: '易孕期与排卵标记暂不可用' }));
      }
    }

    async function load() {
      if (state.refreshing) return;
      state.refreshing = true;
      state.loading = true;
      state.error = null;
      state.skeleton = false;
      if (!state.loaded) {
        paint();
        state.timer = setTimeout(function () {
          if (state.alive && state.loading) {
            state.skeleton = true;
            paint();
          }
        }, 400);
      }
      var results = await Promise.allSettled([
        api.getCycles(),
        api.fertileWindow(),
        api.currentPrediction()
      ]);
      if (!state.alive) return;
      state.refreshing = false;
      state.loading = false;
      clearTimer();
      var cyclesResult = results[0];
      if (cyclesResult.status !== 'fulfilled' || !Array.isArray(cyclesResult.value)) {
        state.error = cyclesResult.reason || { detail: copy.genericError };
        state.cycles = null;
        state.loaded = false;
        state.skeleton = false;
        paint();
        return;
      }
      state.cycles = sortCycles(cyclesResult.value);
      state.fertile = results[1].status === 'fulfilled' ? results[1].value : null;
      state.pred = results[2].status === 'fulfilled' ? results[2].value : null;
      state.loaded = true;
      state.skeleton = false;
      paint();
    }

    page.refresh = function () { return load(); };
    page.destroy = function () {
      state.alive = false;
      clearTimer();
      root.removeEventListener('touchstart', onTouchStart);
      root.removeEventListener('touchend', onTouchEnd);
    };
    load();
    return page;
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.pages = window.PIQ.pages || {};
  window.PIQ.pages.calendar = { render: render };
})();
