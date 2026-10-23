(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var art = window.PIQ.art;
  var copy = window.PIQ.copy;
  var charts = window.PIQ.charts;
  var h = ui.h;

  var STATUS = {
    EMPTY: '无记录',
    COLD: '冷启动',
    WARMING: '学习中',
    STABLE: '稳定',
    MATURE: '成熟',
    ANOMALY_ALERT: '需留意'
  };
  var TREND = { improving: '近期更准', stable: '表现稳定', degrading: '近期波动' };

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

  function oneDecimal(value) {
    var number = finite(value);
    return number === null ? '—' : number.toFixed(1);
  }

  function percentText(value) {
    var number = finite(value);
    if (number === null) return '—';
    return Math.round(Math.max(0, Math.min(1, number)) * 100) + '%';
  }

  function biasText(value) {
    var number = finite(value);
    if (number === null) return '—';
    var absolute = Math.round(Math.abs(number) * 10) / 10;
    if (absolute === 0) return '准确';
    return (number > 0 ? '偏晚 ' : '偏早 ') + absolute.toFixed(1) + ' 天';
  }

  function formatDate(iso) {
    var date = ui.parseISO(iso);
    return date ? (date.getMonth() + 1) + '/' + date.getDate() : '';
  }

  function metricCell(label, value, unit) {
    return h('div', { class: 'kpi-cell glass-surface' }, [
      h('span', { class: 'kpi-label', text: label }),
      h('span', { class: 'kpi-value' }, [
        h('span', { class: 'numeric', text: value }),
        unit ? h('span', { class: 'kpi-unit', text: unit }) : null
      ])
    ]);
  }

  function render(container, ctx) {
    var root = h('div', { class: 'page data-page analysis-page' });
    container.appendChild(root);
    var state = {
      alive: true,
      loading: false,
      loaded: false,
      refreshing: false,
      stats: null,
      cycles: null,
      accuracy: null,
      predictions: null,
      statsError: null,
      cyclesError: false,
      accuracyError: false,
      predictionsError: false,
      timer: null
    };
    var page = {};

    function clearTimer() {
      if (state.timer) { clearTimeout(state.timer); state.timer = null; }
    }

    function skeleton() {
      return h('div', { class: 'skeleton-stack' }, [
        h('div', { class: 'skeleton analysis-skeleton-hero' }),
        h('div', { class: 'skeleton skeleton-line' }),
        h('div', { class: 'skeleton skeleton-line' }),
        h('div', { class: 'skeleton' }),
        h('div', { class: 'skeleton' })
      ]);
    }

    function hero(stats) {
      var average = finite(stats.avg_cycle_length);
      var status = STATUS[stats.status] || '';
      var anomaly = stats.status === 'ANOMALY_ALERT';
      var card = h('section', { class: 'analysis-hero glass-focus' }, [
        h('div', { class: 'analysis-hero-main' }, [
          h('div', { class: 'data-kicker', text: '平均周期' }),
          h('div', { class: 'analysis-avg-row' }, [
            h('span', { class: 'analysis-avg numeric', text: average === null ? '—' : numberText(average) }),
            average === null ? null : h('span', { class: 'analysis-unit', text: '天' })
          ]),
          h('div', { class: 'analysis-basis', text: stats.total_cycles > 0 ? '基于 ' + stats.total_cycles + ' 个完整周期' : '记录两次经期后开始计算' })
        ]),
        status ? h('span', { class: 'badge' + (anomaly ? ' badge-danger' : ''), text: status }) : null
      ]);
      if (anomaly && stats.status_message) {
        return h('section', { class: 'page-section' }, [
          card,
          h('p', { class: 'analysis-status-note', text: String(stats.status_message) })
        ]);
      }
      return h('section', { class: 'page-section' }, [card]);
    }

    function kpiSection(stats) {
      return h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '数据概览' }),
        h('div', { class: 'kpi-grid' }, [
          metricCell('总记录', numberText(stats.total_records), ''),
          metricCell('完整周期', numberText(stats.total_cycles), ''),
          metricCell('平均经期', numberText(stats.avg_period_days), stats.avg_period_days !== null && stats.avg_period_days !== undefined ? '天' : ''),
          metricCell('最短周期', numberText(stats.shortest_cycle), stats.shortest_cycle !== null && stats.shortest_cycle !== undefined ? '天' : ''),
          metricCell('最长周期', numberText(stats.longest_cycle), stats.longest_cycle !== null && stats.longest_cycle !== undefined ? '天' : ''),
          metricCell('异常记录', numberText(stats.anomalous_count), '')
        ])
      ]);
    }

    function trendSection(cycles, stats) {
      if (state.cyclesError) {
        return h('section', { class: 'page-section' }, [
          h('div', { class: 'card' }, [h('p', { class: 'degrade-note', text: '暂时无法读取周期趋势' })])
        ]);
      }
      var valid = cycles.slice().sort(function (a, b) {
        return String(a.period_start).localeCompare(String(b.period_start));
      }).filter(function (cycle) { return finite(cycle.cycle_length) !== null; });
      valid = valid.slice(-12);
      if (valid.length < 2) return null;
      var average = finite(stats.avg_cycle_length);
      var points = valid.map(function (cycle) {
        return {
          label: formatDate(cycle.period_start),
          value: Number(cycle.cycle_length),
          title: ui.fullDate(cycle.period_start) + ' · 周期 ' + numberText(cycle.cycle_length) + ' 天'
        };
      });
      return h('section', { class: 'page-section' }, [
        h('div', { class: 'card chart-card' }, [
          h('div', { class: 'chart-head' }, [
            h('h2', { class: 'section-title', text: '周期长度趋势' }),
            h('span', { class: 'chart-meta', text: '最近 ' + points.length + ' 个' })
          ]),
          charts.line({
            points: points,
            average: average,
            unit: '天',
            ariaLabel: '周期长度趋势图，' + points.length + ' 个完整周期',
            emptyText: '数据不足，继续记录后更新'
          }),
          h('p', { class: 'chart-note', text: '按记录时间从早到晚' })
        ])
      ]);
    }

    function accuracyFour(accuracy) {
      var cells = [
        { label: '已验证', value: numberText(accuracy.evaluated), unit: '' },
        { label: '平均误差', value: oneDecimal(accuracy.mae), unit: '天' },
        { label: '区间覆盖', value: percentText(accuracy.interval_coverage), unit: '' },
        { label: '偏差', value: biasText(accuracy.bias), unit: '' }
      ];
      return h('div', { class: 'accuracy-grid' }, cells.map(function (cell) {
        return h('div', { class: 'accuracy-cell' }, [
          h('span', { class: 'kpi-label', text: cell.label }),
          h('span', { class: 'accuracy-value' }, [
            h('span', { class: 'numeric', text: cell.value }),
            cell.unit ? h('span', { class: 'kpi-unit', text: cell.unit }) : null
          ])
        ]);
      }));
    }

    function hitRates(accuracy) {
      var rows = [1, 2, 3, 5, 7].map(function (days) {
        var value = finite(accuracy['hit_rate_pm' + days]);
        var width = value === null ? 0 : Math.round(Math.max(0, Math.min(1, value)) * 100);
        return h('div', { class: 'hit-row' }, [
          h('span', { class: 'hit-label', text: '±' + days + ' 天' }),
          h('span', { class: 'hit-track' }, [
            h('span', {
              class: 'hit-fill',
              style: 'width:' + width + '%'
            })
          ]),
          h('span', { class: 'hit-value numeric', text: value === null ? '—' : width + '%' })
        ]);
      });
      return h('div', { class: 'hit-list' }, rows);
    }

    function errorChart(accuracy, predictions) {
      if (state.predictionsError) {
        return h('div', { class: 'accuracy-error-chart' }, [
          h('h3', { class: 'chart-subtitle', text: '最近预测误差' }),
          h('p', { class: 'degrade-note', text: '暂时无法读取最近预测误差' })
        ]);
      }
      var points = (Array.isArray(predictions) ? predictions : [])
        .filter(function (item) {
          return item && item.actual_date && finite(item.error_days) !== null;
        })
        .sort(function (a, b) {
          return String(a.predicted_date || '').localeCompare(String(b.predicted_date || ''));
        })
        .slice(-12)
        .map(function (item) {
          return {
            label: formatDate(item.predicted_date),
            value: Math.abs(Number(item.error_days)),
            title: '预测 ' + ui.fullDate(item.predicted_date) + ' · 误差 ' + numberText(item.error_days) + ' 天'
          };
        });
      if (!points.length) return null;
      return h('div', { class: 'accuracy-error-chart' }, [
        h('h3', { class: 'chart-subtitle', text: '最近预测误差' }),
        charts.bars({
          points: points,
          reference: finite(accuracy.mae),
          unit: '天',
          ariaLabel: '最近 ' + points.length + ' 次预测误差柱状图',
          emptyText: '误差数据不足'
        })
      ]);
    }

    function accuracySection(accuracy, predictions) {
      var header = h('div', { class: 'chart-head' }, [
        h('h2', { class: 'section-title', text: '预测精度' }),
        accuracy && !state.accuracyError && TREND[accuracy.trend]
          ? h('span', { class: 'badge', text: TREND[accuracy.trend] })
          : null
      ]);
      var content;
      if (state.accuracyError) {
        content = h('p', { class: 'degrade-note', text: '暂时无法读取预测精度' });
      } else if (!accuracy) {
        content = h('p', { class: 'degrade-note', text: '暂时无法读取预测精度' });
      } else if (accuracy.error) {
        content = h('div', { class: 'accuracy-empty' }, [
          h('p', { class: 'accuracy-empty-title', text: '还没有已验证的预测。' }),
          h('p', { class: 'accuracy-empty-text', text: '当预测日过去并记录新的经期后，这里会开始统计偏差。' })
        ]);
      } else {
        content = [
          accuracyFour(accuracy),
          hitRates(accuracy),
          errorChart(accuracy, predictions)
        ];
      }
      return h('section', { class: 'page-section' }, [
        h('div', { class: 'card accuracy-card' }, [header, content])
      ]);
    }

    function clinicalSection(stats) {
      var notes = Array.isArray(stats.clinical_notes) ? stats.clinical_notes : [];
      if (!notes.length) return null;
      return h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '临床观察' }),
        h('div', { class: 'card' }, [
          h('ul', { class: 'clinical-list' }, notes.map(function (note) {
            return h('li', {
              class: 'clinical-item' + (note.severity === 'attention' ? ' is-attention' : ''),
              text: String(note.message || '')
            });
          })),
          h('p', { class: 'clinical-foot', text: '以下提示由你的记录自动派生，仅供关注健康参考，不构成诊断。' })
        ])
      ]);
    }

    function algorithmSection() {
      return h('section', { class: 'page-section' }, [
        h('details', { class: 'algo-details card' }, [
          h('summary', { class: 'algo-summary', text: '预测是怎么算的' }),
          h('div', { class: 'algo-body' }, [
            h('p', { text: '历史周期长度先经过贝叶斯收缩，再与指数加权平均结合，最后以最后一次经期开始日推算下一次预计日期。' }),
            h('p', { text: '预测区间会随周期长度的波动而放大；记录越稳定，区间通常越窄。' }),
            h('p', { class: 'algo-warning', text: '这是统计推算，不是医学诊断，也不能作为避孕依据。' })
          ])
        ])
      ]);
    }

    function emptyState() {
      return h('div', { class: 'card empty' }, [
        h('img', {
          class: 'empty-art',
          src: art.emptyPlanetViolet,
          alt: '',
          'aria-hidden': 'true',
          width: '160',
          height: '160'
        }),
        h('div', { class: 'empty-title', text: '数据还不够' }),
        h('div', { class: 'empty-text', text: '先记录经期，分析会在你积累周期后自动出现。' }),
        h('button', {
          class: 'btn btn-primary btn-block',
          type: 'button',
          onclick: function () {
            window.PIQ.pages.record.open({ mode: 'start', onDone: function () { ctx.refresh(); } });
          }
        }, '去记录')
      ]);
    }

    function paint() {
      if (!state.alive) return;
      root.textContent = '';
      if (state.loading && !state.loaded) {
        root.appendChild(skeleton());
        return;
      }
      if (state.statsError && !state.loaded) {
        root.appendChild(ui.errorCard(ui.safeMessage(state.statsError, copy.genericError), '重试', function () { load(); }));
        return;
      }
      if (!state.stats) return;
      if (Number(state.stats.total_records) === 0) {
        root.appendChild(emptyState());
        return;
      }
      root.appendChild(hero(state.stats));
      root.appendChild(kpiSection(state.stats));
      var trend = trendSection(state.cycles || [], state.stats);
      if (trend) root.appendChild(trend);
      root.appendChild(accuracySection(state.accuracy, state.predictions));
      var clinical = clinicalSection(state.stats);
      if (clinical) root.appendChild(clinical);
      root.appendChild(algorithmSection());
    }

    async function load() {
      if (state.refreshing) return;
      state.refreshing = true;
      state.loading = true;
      state.statsError = null;
      if (!state.loaded) {
        state.timer = setTimeout(function () {
          if (state.alive && state.loading) paint();
        }, 400);
      }
      var results = await Promise.allSettled([
        api.cycleStats(),
        api.getCycles(),
        api.accuracy(),
        api.predictionHistory()
      ]);
      if (!state.alive) return;
      state.refreshing = false;
      state.loading = false;
      clearTimer();
      var stats = results[0];
      if (stats.status !== 'fulfilled' || !stats.value || typeof stats.value !== 'object') {
        state.statsError = stats.reason || { detail: copy.genericError };
        state.stats = null;
        state.loaded = false;
        paint();
        return;
      }
      state.stats = stats.value;
      state.cyclesError = results[1].status !== 'fulfilled' || !Array.isArray(results[1].value);
      state.cycles = state.cyclesError ? [] : results[1].value;
      state.accuracyError = results[2].status !== 'fulfilled';
      state.accuracy = state.accuracyError ? null : results[2].value;
      state.predictionsError = results[3].status !== 'fulfilled' || !Array.isArray(results[3].value);
      state.predictions = state.predictionsError ? [] : results[3].value;
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
  window.PIQ.pages.analysis = { render: render };
})();
