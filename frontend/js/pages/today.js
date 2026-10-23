(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var art = window.PIQ.art;
  var copy = window.PIQ.copy;
  var router = window.PIQ.router;
  var h = ui.h;
  var SVG_NS = 'http://www.w3.org/2000/svg';

  var RING_SIZE = 220;
  var RING_CENTER = 110;
  var RING_RADIUS = 92;
  var RING_DAYS = 28;

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
    } else if (pred && pred.predicted_date) {
      fill(pred.predicted_date, pred.predicted_date, 'predicted');
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

  function percentOf(value) {
    if (value === null || value === undefined) return null;
    var raw = Number(value);
    if (!Number.isFinite(raw)) return null;
    var percent = raw <= 1 ? Math.round(raw * 100) : Math.round(raw);
    return Math.max(0, Math.min(100, percent));
  }

  function modelLabel(pred) {
    var version = String((pred && pred.model_version) || '');
    var params = pred && pred.model_params ? pred.model_params : {};
    if (version.indexOf('cold_start') === 0) return '学习中';
    if (version === 'bayesian_v1' || params.bayes_applied === true) return '已学习';
    return version ? '学习中' : '暂无数据';
  }

  function cycleStart(cycles) {
    return cycles.length && cycles[0].period_start ? cycles[0].period_start : null;
  }

  function cycleDayOf(start, today) {
    if (!start) return 1;
    var day = ui.diffDays(start, today) + 1;
    return Number.isFinite(day) ? Math.max(1, day) : 1;
  }

  function ringNode(args) {
    var start = args.start;
    var today = args.today;
    var cycles = args.cycles;
    var fertile = args.fertile;
    var pred = args.pred;
    var days = args.days;
    var overdue = days < 0;
    var cycleDay = cycleDayOf(start, today);
    var index = buildPhaseIndex(cycles, fertile, pred);
    var predictedIndex = start && pred && pred.predicted_date
      ? ui.diffDays(start, pred.predicted_date)
      : RING_DAYS;

    var wrapper = h('div', { class: 'ring' });
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'ring-svg');
    svg.setAttribute('viewBox', '0 0 ' + RING_SIZE + ' ' + RING_SIZE);
    svg.setAttribute('aria-hidden', 'true');
    var circumference = 2 * Math.PI * RING_RADIUS;
    var slot = circumference / RING_DAYS;
    var arc = Math.max(2, slot - 3.8);

    for (var i = 0; i < RING_DAYS; i += 1) {
      var date = start ? ui.addDays(start, i) : null;
      var phase = date ? index[date] : '';
      var cls = 'ring-seg';
      if (phase) cls += ' is-' + phase;
      if (overdue) {
        if (predictedIndex >= 0 && predictedIndex < RING_DAYS) {
          if (i === predictedIndex) cls = 'ring-seg is-gap';
          else if (i > predictedIndex) cls += ' is-overdue';
        } else if (i >= RING_DAYS - 3) {
          cls = i === RING_DAYS - 1 ? 'ring-seg is-gap' : 'ring-seg is-overdue';
        }
      }
      var circle = document.createElementNS(SVG_NS, 'circle');
      circle.setAttribute('class', cls);
      circle.setAttribute('cx', String(RING_CENTER));
      circle.setAttribute('cy', String(RING_CENTER));
      circle.setAttribute('r', String(RING_RADIUS));
      circle.setAttribute('stroke-dasharray', arc + ' ' + (circumference - arc));
      circle.setAttribute('stroke-dashoffset', String(-i * slot));
      circle.setAttribute('transform', 'rotate(-90 ' + RING_CENTER + ' ' + RING_CENTER + ')');
      circle.style.setProperty('--i', String(i));
      svg.appendChild(circle);
    }

    var todayIndex = Math.max(0, Math.min(RING_DAYS - 1, cycleDay - 1));
    var angle = (todayIndex / RING_DAYS) * Math.PI * 2 - Math.PI / 2;
    var tx = RING_CENTER + Math.cos(angle) * RING_RADIUS;
    var ty = RING_CENTER + Math.sin(angle) * RING_RADIUS;
    var halo = document.createElementNS(SVG_NS, 'circle');
    halo.setAttribute('class', 'ring-today-halo');
    halo.setAttribute('cx', String(tx));
    halo.setAttribute('cy', String(ty));
    halo.setAttribute('r', '13');
    var core = document.createElementNS(SVG_NS, 'circle');
    core.setAttribute('class', 'ring-today-core');
    core.setAttribute('cx', String(tx));
    core.setAttribute('cy', String(ty));
    core.setAttribute('r', '5.5');
    var shade = document.createElementNS(SVG_NS, 'circle');
    shade.setAttribute('class', 'ring-today-moon');
    shade.setAttribute('cx', String(tx + 1.6));
    shade.setAttribute('cy', String(ty - 1.0));
    shade.setAttribute('r', '3.4');
    shade.setAttribute('opacity', '.78');
    svg.appendChild(halo);
    svg.appendChild(core);
    svg.appendChild(shade);
    wrapper.appendChild(svg);

    var cycleKicker = h('span', { class: 'ring-kicker' });
    var number = h('span', { class: 'ring-num' });
    var unit = h('span', { class: 'ring-sub' });
    var center = h('button', {
      class: 'ring-center',
      type: 'button',
      'aria-label': '切换查看周期天数和距下次经期'
    }, [cycleKicker, number, unit, h('span', { class: 'ring-hint', text: '轻点切换' })]);

    var showNext = false;
    function paint() {
      if (showNext) {
        if (days === 0) {
          cycleKicker.textContent = '预计';
          number.textContent = '今天';
          unit.textContent = '';
        } else if (overdue) {
          cycleKicker.textContent = '已逾期';
          number.textContent = String(Math.abs(days));
          unit.textContent = '天';
        } else {
          cycleKicker.textContent = '距下次';
          number.textContent = String(days);
          unit.textContent = '天';
        }
      } else {
        cycleKicker.textContent = '周期第';
        number.textContent = String(cycleDay);
        unit.textContent = '天';
      }
      center.setAttribute('aria-label', '当前' + (showNext ? '距下次经期 ' + Math.abs(days) + ' 天' : '周期第 ' + cycleDay + ' 天') + '，轻点切换');
    }
    paint();
    center.addEventListener('click', function () {
      showNext = !showNext;
      center.classList.add('is-swapping');
      paint();
      setTimeout(function () { center.classList.remove('is-swapping'); }, 180);
    });
    wrapper.appendChild(center);
    return wrapper;
  }

  function heroTrack(pred) {
    if (!pred.predicted_start_lower || !pred.predicted_start_upper) return null;
    var lower = pred.predicted_start_lower;
    var upper = pred.predicted_start_upper;
    var position = 58;
    if (pred.predicted_date && lower !== upper) {
      var total = ui.diffDays(lower, upper);
      var offset = ui.diffDays(lower, pred.predicted_date);
      if (total > 0 && Number.isFinite(offset)) position = Math.max(4, Math.min(96, offset / total * 100));
    }
    return h('div', { class: 'hero-interval' }, [
      h('div', {
        class: 'hero-track',
        style: '--piq-track-pos:' + position + '%',
        'aria-hidden': 'true'
      }),
      h('div', { class: 'hero-track-labels' }, [
        h('span', { text: '最早 ' + ui.monthDay(lower) }),
        h('span', { text: '最晚 ' + ui.monthDay(upper) })
      ])
    ]);
  }

  function render(container) {
    var root = h('div', { class: 'page today-page' });
    container.appendChild(root);
    var state = { alive: true, loading: false, loaded: false, skeletonTimer: null, refreshing: false };
    var page = { cycles: [] };

    function clearSkeleton() {
      if (state.skeletonTimer) {
        clearTimeout(state.skeletonTimer);
        state.skeletonTimer = null;
      }
    }

    function showSkeleton() {
      clearSkeleton();
      root.textContent = '';
      root.appendChild(h('div', { class: 'skeleton-stack' }, [
        h('div', { class: 'skeleton skeleton-hero' }),
        h('div', { class: 'skeleton' }),
        h('div', { class: 'skeleton' })
      ]));
    }

    function showFatal(err) {
      root.textContent = '';
      root.appendChild(ui.errorCard(ui.safeMessage(err, copy.genericError), '重试', function () {
        load(false);
      }));
    }

    function recordButton(label, mode) {
      return h('button', {
        class: 'btn btn-hero btn-block',
        type: 'button',
        onclick: function () {
          window.PIQ.pages.record.open({ mode: mode || 'start', onDone: function () { load(false); } });
        }
      }, [ui.icon('record'), label]);
    }

    function renderEmpty() {
      root.textContent = '';
      root.appendChild(h('div', { class: 'empty' }, [
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
        h('div', { class: 'empty-text', text: copy.emptyToday }),
        recordButton(copy.recordStart, 'start'),
        h('div', { class: 'empty-hint', text: copy.emptyHint })
      ]));
    }

    function renderHero(pred) {
      var predicted = pred.predicted_date;
      var days = Number(pred.days_until_predicted);
      if (!Number.isFinite(days)) days = ui.diffDays(ui.todayISO(), predicted);
      var today = ui.todayISO();
      var cycles = page.cycles || [];
      var start = cycleStart(cycles);
      var cycleDay = cycleDayOf(start, today);
      var overdue = days < 0;

      var hero = h('section', { class: 'hero' + (days <= -7 ? ' has-advice' : '') }, [
        h('img', {
          class: 'hero-art',
          src: art.heroPlanet,
          alt: '',
          'aria-hidden': 'true',
          width: '230',
          height: '230'
        }),
        h('img', {
          class: 'hero-star',
          src: art.starGold,
          alt: '',
          'aria-hidden': 'true',
          width: '36',
          height: '36'
        }),
        h('div', { class: 'hero-orbit', 'aria-hidden': 'true' }, [
          h('span', { class: 'hero-geo-star' }),
          h('span', { class: 'hero-geo-dot' })
        ])
      ]);
      var body = h('div', { class: 'hero-body' });
      body.appendChild(ringNode({
        start: start,
        today: today,
        cycles: cycles,
        fertile: page.fertile,
        pred: pred,
        days: days
      }));

      body.appendChild(h('div', {
        class: 'hero-date',
        text: '预计 ' + ui.monthDay(predicted)
      }));
      var countdown = days > 0 ? '还有 ' + days + ' 天' : (days === 0 ? '预计今天开始' : '已逾期 ' + Math.abs(days) + ' 天');
      body.appendChild(h('div', { class: 'hero-countdown', text: countdown }));

      var percent = percentOf(pred.confidence);
      var complete = cycles.filter(function (cycle) {
        return cycle.cycle_length !== null && cycle.cycle_length !== undefined;
      }).length;
      var confidenceText = percent === null
        ? '模型学习中 · 基于 ' + cycles.length + ' 条记录'
        : percent + '% · ' + modelLabel(pred) + ' · ' + complete + ' 个完整周期';
      body.appendChild(h('div', { class: 'hero-confidence', text: confidenceText }));

      if (days <= -7) {
        body.appendChild(h('div', { class: 'hero-advice', text: '若持续未来潮，建议验孕或咨询医生。' }));
      }

      var track = heroTrack(pred);
      if (track) body.appendChild(track);

      body.appendChild(h('div', { class: 'hero-actions' }, [
        recordButton(overdue ? '记录经期开始' : '记录今天', 'start')
      ]));
      hero.appendChild(body);
      return hero;
    }

    function renderWeek(cycles, fertile, pred) {
      var index = buildPhaseIndex(cycles, fertile, pred);
      var today = ui.todayISO();
      var todayDate = ui.parseISO(today);
      var offset = (todayDate.getDay() + 6) % 7;
      var weekStart = ui.addDays(today, -offset);
      var cells = [];
      var weekdays = ['一', '二', '三', '四', '五', '六', '日'];
      for (var i = 0; i < 7; i += 1) {
        var date = ui.addDays(weekStart, i);
        var phase = index[date] || '';
        var classes = 'week-cell' + (date === today ? ' is-today' : '');
        cells.push(h('div', {
          class: classes,
          role: 'img',
          'aria-label': ui.fullDate(date) + (phase ? '，' + PHASE_LABEL[phase] : '，无标记')
        }, [
          h('span', { class: 'week-weekday', text: weekdays[i] }),
          h('span', { class: 'week-day', text: String(ui.parseISO(date).getDate()) }),
          h('span', { class: 'week-mark' + (phase ? ' is-' + phase : ''), 'aria-hidden': 'true' })
        ]));
      }
      return h('section', { class: 'page-section' }, [
        h('div', { class: 'card glass-surface week-card' }, [
          h('div', { class: 'week-head' }, [
            h('h2', { class: 'section-title', text: '本周' }),
            h('button', {
              class: 'week-link',
              type: 'button',
              onclick: function () { router.go('calendar'); }
            }, '打开日历')
          ]),
          h('div', { class: 'week-strip' }, cells),
          h('div', { class: 'legend' }, [
            legendItem('is-period', '经期'),
            legendItem('is-predicted', '预测'),
            legendItem('is-fertile', '易孕'),
            legendItem('is-ovulation', '排卵')
          ])
        ])
      ]);
    }

    function legendItem(cls, label) {
      return h('span', { class: 'legend-item' }, [
        h('span', { class: 'legend-dot ' + cls, 'aria-hidden': 'true' }),
        label
      ]);
    }

    function renderInsights(stats) {
      var notes = stats && Array.isArray(stats.clinical_notes) ? stats.clinical_notes : [];
      if (!notes.length) return null;
      var items = notes.slice(0, 3).map(function (note) {
        return h('li', { text: note.message || '' });
      });
      return h('section', { class: 'page-section' }, [
        h('details', { class: 'card glass-surface insight-details' }, [
          h('summary', { class: 'insight-summary', text: '给你的观察' }),
          h('div', { class: 'insight-body' }, [
            h('ul', { class: 'insight-list' }, items),
            h('div', { class: 'insight-foot', text: copy.disclaimer })
          ])
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
        }, '重新计算预测')
      ]);
    }

    function renderNoPrediction() {
      return h('section', { class: 'empty' }, [
        h('div', { class: 'empty-art-wrap' }, [
          h('img', {
            class: 'empty-art',
            src: art.emptyPlanetBlue,
            alt: '',
            'aria-hidden': 'true',
            width: '84',
            height: '84'
          })
        ]),
        h('div', { class: 'empty-title', text: '预测还在学习' }),
        h('div', { class: 'empty-text', text: '再记录一条完整周期，模型就能给出下一周期预测。' }),
        recordButton('记录经期开始', 'start')
      ]);
    }

    function renderContent(pred, cycles, fertile, stats) {
      root.textContent = '';
      if (!cycles.length) {
        renderEmpty();
        return;
      }
      page.cycles = cycles;
      page.fertile = fertile;
      var hasPrediction = pred && pred.predicted_date;
      if (hasPrediction) {
        root.appendChild(renderHero(pred));
      } else {
        root.appendChild(renderNoPrediction());
      }
      root.appendChild(renderWeek(cycles, fertile, pred));
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
