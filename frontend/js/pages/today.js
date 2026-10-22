(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var art = window.PIQ.art;
  var copy = window.PIQ.copy;
  var h = ui.h;
  var SVG_NS = 'http://www.w3.org/2000/svg';

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

  var PHASE_LABEL = { period: '经期', predicted: '预测经期', fertile: '易孕期', ovulation: '排卵日' };

  function ring(progress, overdue) {
    var radius = 80;
    var center = 90;
    var circumference = 2 * Math.PI * radius;
    var clamped = Math.max(0, Math.min(1, progress));
    var wrapper = h('div', { class: 'ring' + (overdue ? ' is-overdue' : '') });
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'ring-svg');
    svg.setAttribute('viewBox', '0 0 180 180');
    svg.setAttribute('aria-hidden', 'true');
    var track = document.createElementNS(SVG_NS, 'circle');
    track.setAttribute('class', 'ring-track');
    track.setAttribute('cx', center);
    track.setAttribute('cy', center);
    track.setAttribute('r', radius);
    var bar = document.createElementNS(SVG_NS, 'circle');
    bar.setAttribute('class', 'ring-progress');
    bar.setAttribute('cx', center);
    bar.setAttribute('cy', center);
    bar.setAttribute('r', radius);
    bar.setAttribute('stroke-dasharray', String(circumference));
    bar.setAttribute('stroke-dashoffset', String(circumference * (1 - clamped)));
    svg.appendChild(track);
    svg.appendChild(bar);
    wrapper.appendChild(svg);
    return wrapper;
  }

  function modelLabel(pred) {
    var version = String((pred && pred.model_version) || '');
    var params = pred && pred.model_params ? pred.model_params : {};
    if (version.indexOf('cold_start') === 0) return '冷启动';
    if (version === 'bayesian_v1' || params.bayes_applied === true) return '已学习';
    return version ? '学习中' : '暂无数据';
  }

  function legendItem(cls, label) {
    return h('span', { class: 'legend-item' }, [
      h('span', { class: 'legend-dot ' + cls, 'aria-hidden': 'true' }),
      label
    ]);
  }

  function render(container) {
    var root = h('div', { class: 'page today-page' });
    container.appendChild(root);
    var state = { alive: true, loading: false, loaded: false, skeletonTimer: null, refreshing: false };
    var page = {};

    function clearSkeleton() {
      if (state.skeletonTimer) {
        clearTimeout(state.skeletonTimer);
        state.skeletonTimer = null;
      }
    }

    function showSkeleton() {
      clearSkeleton();
      root.textContent = '';
      var wrap = h('div', { class: 'skeleton-stack' }, [
        h('div', { class: 'skeleton skeleton-hero' }),
        h('div', { class: 'skeleton' }),
        h('div', { class: 'skeleton' })
      ]);
      root.appendChild(wrap);
    }

    function showFatal(err) {
      root.textContent = '';
      root.appendChild(ui.errorCard(ui.safeMessage(err, copy.genericError), '重试', function () {
        load(false);
      }));
    }

    function renderEmpty() {
      root.textContent = '';
      var empty = h('div', { class: 'card empty' }, [
        h('img', {
          class: 'empty-art',
          src: art.emptyMoon,
          alt: '',
          'aria-hidden': 'true',
          width: '160',
          height: '160'
        }),
        h('div', { class: 'empty-title', text: copy.emptyTitle }),
        h('div', { class: 'empty-text', text: copy.emptyToday }),
        h('button', {
          class: 'btn btn-primary btn-block',
          type: 'button',
          onclick: function () {
            window.PIQ.pages.record.open({ mode: 'start', onDone: function () { load(false); } });
          }
        }, copy.recordStart),
        h('div', { class: 'empty-hint', text: copy.emptyHint })
      ]);
      root.appendChild(empty);
    }

    function renderHero(pred) {
      var predicted = pred.predicted_date;
      var days = Number(pred.days_until_predicted);
      if (!Number.isFinite(days)) days = ui.diffDays(ui.todayISO(), predicted);
      var today = ui.todayISO();
      var cycles = page.cycles || [];
      var lastStart = cycles.length ? cycles[0].period_start : null;
      var progress = 0;
      if (lastStart && predicted) {
        var span = ui.diffDays(lastStart, predicted);
        var elapsed = ui.diffDays(lastStart, today);
        if (span > 0) progress = elapsed / span;
        else progress = 1;
      }
      var hero = h('section', { class: 'hero' + (days <= -7 ? ' has-advice' : '') }, [
        h('img', {
          class: 'hero-art',
          src: art.heroPlanet,
          alt: '',
          'aria-hidden': 'true',
          width: '160',
          height: '160'
        }),
        h('img', {
          class: 'hero-star',
          src: art.starGold,
          alt: '',
          'aria-hidden': 'true',
          width: '48',
          height: '48'
        })
      ]);
      var body = h('div', { class: 'hero-body' });
      var overflow = days < 0;
      var ringNode = ring(progress, overflow);
      var num;
      var sub = '天';
      if (days > 0) num = String(days);
      else if (days === 0) { num = '今天'; sub = ''; }
      else num = String(Math.abs(days));
      var center = h('div', { class: 'ring-center' }, [
        h('div', { class: 'ring-num', text: num }),
        sub ? h('div', { class: 'ring-sub', text: sub }) : null
      ]);
      ringNode.appendChild(center);
      body.appendChild(ringNode);
      body.appendChild(h('div', { class: 'hero-date', text: ui.monthDay(predicted) }));
      var countdown = days > 0 ? '还有 ' + days + ' 天' : (days === 0 ? '预计今天开始' : '已逾期 ' + Math.abs(days) + ' 天');
      body.appendChild(h('div', { class: 'hero-countdown', text: countdown }));
      if (days <= -7) {
        body.appendChild(h('div', { class: 'hero-advice', text: '若持续未来潮，建议验孕或咨询医生。' }));
      }
      hero.appendChild(body);
      return hero;
    }

    function renderConfidence(pred, cycles) {
      if (pred.confidence === null || pred.confidence === undefined) return null;
      var raw = Number(pred.confidence);
      if (!Number.isFinite(raw)) return null;
      var percent = raw <= 1 ? Math.round(raw * 100) : Math.round(raw);
      percent = Math.max(0, Math.min(100, percent));
      var complete = cycles.filter(function (cycle) { return cycle.cycle_length !== null && cycle.cycle_length !== undefined; }).length;
      return h('section', { class: 'card' }, [
        h('div', { class: 'card-row' }, [
          h('div', { class: 'section-title', text: '预测置信度' }),
          h('div', { class: 'confidence-value numeric', text: percent + '%' })
        ]),
        h('div', { class: 'confidence-meta' }, [
          h('span', { class: 'pill', text: modelLabel(pred) }),
          h('span', { class: 'card-muted', text: '基于 ' + cycles.length + ' 条记录：' + complete + ' 个完整周期' })
        ])
      ]);
    }

    function renderInterval(pred) {
      if (!pred.predicted_start_lower || !pred.predicted_start_upper) return null;
      return h('section', { class: 'page-section' }, [
        h('h3', { class: 'section-title', text: '预测区间' }),
        h('div', { class: 'card' }, [
          h('div', { class: 'interval-grid' }, [
            h('div', { class: 'interval-end' }, [
              h('div', { class: 'interval-date', text: ui.monthDay(pred.predicted_start_lower) }),
              h('div', { class: 'interval-cap', text: '最早' })
            ]),
            h('div', { class: 'interval-bridge', 'aria-hidden': 'true' }),
            h('div', { class: 'interval-end is-end' }, [
              h('div', { class: 'interval-date', text: ui.monthDay(pred.predicted_start_upper) }),
              h('div', { class: 'interval-cap', text: '最晚' })
            ])
          ])
        ])
      ]);
    }

    function renderDots(cycles, fertile, pred) {
      var index = buildPhaseIndex(cycles, fertile, pred);
      var today = ui.todayISO();
      var todayDate = ui.parseISO(today);
      var weekStart = ui.addDays(today, -todayDate.getDay());
      var cells = [];
      for (var i = 0; i < 7; i += 1) {
        var date = ui.addDays(weekStart, i);
        var phase = index[date] || '';
        var classes = 'dot-cell' + (phase ? ' is-' + phase : '') + (date === today ? ' is-today' : '');
        var label = ui.fullDate(date) + (phase ? '，' + PHASE_LABEL[phase] : '，无标记');
        cells.push(h('div', {
          class: classes,
          role: 'img',
          'aria-label': label
        }, [
          h('span', { class: 'dot-day', text: String(ui.parseISO(date).getDate()) }),
          h('span', { class: 'dot-mark', 'aria-hidden': 'true' })
        ]));
      }
      return h('section', { class: 'page-section' }, [
        h('h3', { class: 'section-title', text: '周期点阵概览' }),
        h('div', { class: 'card' }, [
          h('div', { class: 'dots' }, cells),
          h('div', { class: 'legend' }, [
            legendItem('is-period', '经期'),
            legendItem('is-predicted', '预测'),
            legendItem('is-fertile', '易孕'),
            legendItem('is-ovulation', '排卵')
          ])
        ])
      ]);
    }

    function renderInsights(stats) {
      var notes = stats && Array.isArray(stats.clinical_notes) ? stats.clinical_notes : [];
      if (!notes.length) return null;
      var items = notes.slice(0, 2).map(function (note) {
        return h('li', { text: note.message || '' });
      });
      return h('section', { class: 'page-section' }, [
        h('h3', { class: 'section-title', text: '给你的观察' }),
        h('div', { class: 'card' }, [
          h('ul', { class: 'insight-list' }, items),
          h('div', { class: 'insight-foot', text: copy.disclaimer })
        ])
      ]);
    }

    function renderRefresh(onRefresh) {
      return h('div', { class: 'text-row' }, [
        h('button', {
          class: 'btn btn-text',
          type: 'button',
          disabled: state.refreshing,
          onclick: onRefresh
        }, '刷新预测')
      ]);
    }

    function renderNoPrediction() {
      return h('section', { class: 'card' }, [
        h('h3', { class: 'card-title', text: '预测还在学习' }),
        h('p', { class: 'card-muted', text: '再记录一条完整周期，模型就能给出下一周期预测。' })
      ]);
    }

    function renderContent(pred, cycles, fertile, stats) {
      root.textContent = '';
      if (!cycles.length) {
        renderEmpty();
        return;
      }
      page.cycles = cycles;
      var hasPrediction = pred && pred.predicted_date;
      if (hasPrediction) {
        root.appendChild(renderHero(pred));
        var confidence = renderConfidence(pred, cycles);
        if (confidence) root.appendChild(confidence);
        var interval = renderInterval(pred);
        if (interval) root.appendChild(interval);
      } else {
        root.appendChild(renderNoPrediction());
      }
      root.appendChild(renderDots(cycles, fertile, pred));
      var insights = renderInsights(stats);
      if (insights) root.appendChild(insights);
      root.appendChild(renderRefresh(function () { load(true); }));
    }

    async function load(showToast) {
      if (state.refreshing) return;
      state.refreshing = true;
      state.loading = true;
      if (!state.loaded) {
        state.skeletonTimer = setTimeout(function () {
          if (state.alive && state.loading) showSkeleton();
        }, 400);
      }
      var results = await Promise.allSettled([
        api.currentPrediction(),
        api.getCycles(),
        api.fertileWindow(),
        api.cycleStats()
      ]);
      if (!state.alive) return;
      state.loading = false;
      state.refreshing = false;
      clearSkeleton();
      var cyclesResult = results[1];
      if (cyclesResult.status !== 'fulfilled' || !Array.isArray(cyclesResult.value)) {
        showFatal(cyclesResult.reason || { detail: copy.genericError });
        return;
      }
      var pred = results[0].status === 'fulfilled' ? results[0].value : null;
      var fertile = results[2].status === 'fulfilled' ? results[2].value : null;
      var stats = results[3].status === 'fulfilled' ? results[3].value : null;
      state.loaded = true;
      renderContent(pred, sortCycles(cyclesResult.value), fertile, stats);
      if (showToast) ui.toast('已刷新');
    }

    page.refresh = function () { return load(false); };
    page.destroy = function () { state.alive = false; clearSkeleton(); };
    load(false);
    return page;
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.pages = window.PIQ.pages || {};
  window.PIQ.pages.today = { render: render };
})();
