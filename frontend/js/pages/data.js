(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var router = window.PIQ.router;
  var h = ui.h;
  var MAX_FILE_BYTES = 10 * 1024 * 1024;
  /* 弹层关闭会触发 history.back；本地导入毫秒级完成，立即跳转会被这次回退覆盖。 */
  var NAV_AFTER_MODAL_MS = 350;

  function stamp() {
    var now = new Date();
    return ui.isoOf(now).replace(/-/g, '') + '-' + ui.pad2(now.getHours()) + ui.pad2(now.getMinutes());
  }

  function downloadBlob(name, blob) {
    var url = URL.createObjectURL(blob);
    var anchor = h('a', {
      class: 'file-input-hidden',
      href: url,
      download: name,
      'aria-hidden': 'true'
    });
    document.body.appendChild(anchor);
    anchor.click();
    setTimeout(function () {
      if (anchor.parentNode) anchor.parentNode.removeChild(anchor);
      URL.revokeObjectURL(url);
    }, 12000);
  }

  function sortCyclesAsc(cycles) {
    return (Array.isArray(cycles) ? cycles.slice() : []).sort(function (a, b) {
      var start = String(a && a.period_start || '').localeCompare(String(b && b.period_start || ''));
      if (start !== 0) return start;
      return Number(a && a.id || 0) - Number(b && b.id || 0);
    });
  }

  function csvDate(iso) {
    return String(iso || '').slice(0, 10);
  }

  function fileSizeLabel(file) {
    var size = file && Number(file.size) ? Number(file.size) : 0;
    if (size < 1024) return size + ' B';
    if (size < 1024 * 1024) return Math.round(size / 1024) + ' KB';
    return (Math.round(size / (1024 * 1024) * 10) / 10) + ' MB';
  }

  function page(lead, children) {
    return h('div', { class: 'page settings-page' }, [
      lead ? h('p', { class: 'settings-lead', text: lead }) : null
    ].concat(children || []));
  }

  function render(container) {
    var busy = false;
    var error = h('div', { class: 'field-error', role: 'alert' });
    error.hidden = true;
    var jsonButton = h('button', { class: 'btn btn-primary btn-block', type: 'button' }, '导出 JSON 备份');
    var csvButton = h('button', { class: 'btn btn-block', type: 'button' }, '导出 CSV');
    var importButton = h('button', { class: 'btn btn-block', type: 'button' }, '导入数据');

    function fail(message) {
      error.textContent = message;
      error.hidden = false;
    }

    function run(button, work) {
      if (busy) return;
      busy = true;
      [jsonButton, csvButton, importButton].forEach(function (item) { item.disabled = true; });
      error.hidden = true;
      Promise.resolve().then(work).catch(function (err) {
        fail(ui.safeMessage(err, '操作失败，请稍后重试'));
      }).then(function () {
        busy = false;
        [jsonButton, csvButton, importButton].forEach(function (item) { item.disabled = false; });
      });
    }

    jsonButton.addEventListener('click', function () {
      run(jsonButton, async function () {
        var data = await api.exportData();
        downloadBlob('PeriodIQ-backup-' + stamp() + '.json', new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
        ui.toast('已交给系统导出');
      });
    });
    csvButton.addEventListener('click', function () {
      run(csvButton, async function () {
        var cycles = sortCyclesAsc(await api.getCycles());
        var lines = ['开始日期,结束日期'];
        cycles.forEach(function (cycle) {
          var start = csvDate(cycle && cycle.period_start);
          if (!start) return;
          var end = csvDate(cycle && cycle.period_end);
          lines.push(end ? start + ',' + end : start);
        });
        downloadBlob('PeriodIQ-cycles-' + stamp() + '.csv', new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }));
        ui.toast('已交给系统导出');
      });
    });
    importButton.addEventListener('click', function () { router.go('settings/data/import'); });

    container.appendChild(page('导出用于自己保存或迁移；导入会替换本机现有数据。', [
      h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '导出' }),
        h('div', { class: 'card data-card' }, [
          h('p', { class: 'settings-row-note', text: 'JSON 包含完整备份；CSV 只包含开始日期与结束日期两列。' }),
          jsonButton,
          csvButton
        ])
      ]),
      h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '导入' }),
        h('div', { class: 'card data-card' }, [
          h('p', { class: 'settings-row-note', text: '支持 JSON 备份、CSV 周期表，也可以在应用内手动补录一条记录。' }),
          importButton
        ])
      ]),
      error
    ]));
    return { destroy: function () {} };
  }

  function parseDate(value) {
    var text = String(value || '').trim();
    var match = /^(\d{4})[-/](\d{1,2})[-/](\d{1,2})$/.exec(text);
    if (!match) return null;
    var iso = match[1] + '-' + ui.pad2(match[2]) + '-' + ui.pad2(match[3]);
    var date = ui.parseISO(iso);
    if (!date || ui.isoOf(date) !== iso) return null;
    return iso;
  }

  function parseCsv(text) {
    var source = String(text || '').replace(/^\uFEFF/, '');
    var rows = source.split(/\r?\n/);
    var cycles = [];
    var firstDataLine = true;
    rows.forEach(function (raw, index) {
      var line = raw.trim();
      if (!line) return;
      var cells = line.split(',');
      var start = parseDate(cells[0]);
      if (firstDataLine && !start) {
        firstDataLine = false;
        return;
      }
      firstDataLine = false;
      if (!start) throw new Error('第 ' + (index + 1) + ' 行：开始日期格式不正确');
      if (cells.length > 2) throw new Error('第 ' + (index + 1) + ' 行：只能有两列');
      var end = null;
      if (cells.length === 2 && cells[1].trim()) {
        end = parseDate(cells[1]);
        if (!end) throw new Error('第 ' + (index + 1) + ' 行：结束日期格式不正确');
        if (end < start) throw new Error('第 ' + (index + 1) + ' 行：结束日期早于开始日期');
      }
      cycles.push({ period_start: start, period_end: end });
    });
    if (!cycles.length) throw new Error('CSV 中没有有效数据');
    return { cycles: cycles };
  }

  function renderImport(container, ctx) {
    var alive = true;
    var busy = false;
    var mode = 'json';
    var error = h('div', { class: 'field-error', role: 'alert' });
    error.hidden = true;
    var tabs = h('div', { class: 'import-tabs', role: 'tablist', 'aria-label': '导入方式' });
    var panel = h('div', { class: 'import-panel', role: 'tabpanel' });
    var tabDefs = [
      { id: 'json', label: 'JSON 备份' },
      { id: 'csv', label: 'CSV 周期表' },
      { id: 'manual', label: '手动补录' }
    ];
    var tabButtons = {};

    function fail(message) {
      error.textContent = message;
      error.hidden = false;
      try { error.scrollIntoView({ block: 'nearest' }); } catch (e) { /* 忽略 */ }
    }

    function clearError() {
      error.hidden = true;
      error.textContent = '';
    }

    function setBusy(value) {
      busy = value;
      Array.prototype.forEach.call(panel.querySelectorAll('button, input, textarea'), function (node) {
        node.disabled = value;
      });
      Object.keys(tabButtons).forEach(function (key) { tabButtons[key].disabled = value; });
    }

    function selectTab(id) {
      if (busy) return;
      mode = id;
      Object.keys(tabButtons).forEach(function (key) {
        var active = key === id;
        tabButtons[key].setAttribute('aria-selected', active ? 'true' : 'false');
        tabButtons[key].classList.toggle('is-active', active);
      });
      drawPanel();
    }

    function fileLabel(file) {
      return file ? file.name + ' · ' + fileSizeLabel(file) : '尚未选择文件';
    }

    function oversized(file) {
      if (file && file.size > MAX_FILE_BYTES) {
        fail('文件超过 10 MB，请换一个小一点的文件');
        return true;
      }
      return false;
    }

    function readFile(file, onText) {
      if (oversized(file)) return;
      var reader = new FileReader();
      reader.onerror = function () { fail('文件读取失败，请重试'); };
      reader.onload = function () { onText(String(reader.result || '')); };
      reader.readAsText(file);
    }

    function importPayload(payload) {
      if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
        fail('文件内容不是有效的备份对象');
        return;
      }
      delete payload.allow_anomalies;
      ui.openDialog({
        title: '导入会替换本机数据',
        text: '导入会替换本机现有的全部周期与预测，且不可撤销。建议先导出备份。',
        danger: true,
        cancelLabel: '取消',
        confirmLabel: '继续导入'
      }).then(function (confirmed) {
        if (confirmed) submitImport(payload, false);
      });
    }

    function submitImport(payload, allowAnomalies) {
      if (busy) return;
      setBusy(true);
      clearError();
      if (allowAnomalies) payload.allow_anomalies = true;
      api.importData(payload).then(function (data) {
        if (!alive) return;
        ui.toast(data && data.message ? data.message : '导入成功');
        setTimeout(function () {
          if (alive) router.go('history');
        }, NAV_AFTER_MODAL_MS);
      }).catch(function (err) {
        setBusy(false);
        if (!alive) return;
        if (err && err.code === 'DATA_ANOMALY') {
          var list = err.payload && Array.isArray(err.payload.anomalies) ? err.payload.anomalies : [];
          var messages = list.slice(0, 5).map(function (item, index) {
            return (index + 1) + '. ' + String(item && item.message || '数据异常');
          });
          if (!messages.length) messages.push('后端报告数据异常，但未返回明细。');
          setTimeout(function () {
            if (!alive) return;
            ui.openDialog({
              title: '数据异常，需确认',
              text: messages.join('；'),
              danger: true,
              cancelLabel: '返回',
              confirmLabel: '仍要导入'
            }).then(function (confirmed) {
              if (confirmed) submitImport(payload, true);
            });
          }, NAV_AFTER_MODAL_MS);
          return;
        }
        fail(ui.safeMessage(err, '导入失败，请检查文件内容'));
      });
    }

    function drawPanel() {
      panel.textContent = '';
      if (mode === 'json') {
        var jsonFile = h('input', {
          class: 'file-input-hidden',
          type: 'file',
          accept: '.json,application/json',
          'aria-label': '选择 JSON 备份文件'
        });
        var jsonName = h('div', { class: 'file-name', text: '尚未选择文件' });
        var jsonChoose = h('button', { class: 'btn btn-block', type: 'button' }, '选择 JSON 备份');
        jsonChoose.addEventListener('click', function () { jsonFile.click(); });
        jsonFile.addEventListener('change', function () {
          var file = jsonFile.files && jsonFile.files[0];
          if (!file) return;
          jsonName.textContent = fileLabel(file);
          readFile(file, function (text) {
            var payload;
            try { payload = JSON.parse(text); }
            catch (e) { fail('JSON 解析失败，请检查文件'); return; }
            importPayload(payload);
          });
          jsonFile.value = '';
        });
        panel.appendChild(h('div', { class: 'import-file-card' }, [
          h('div', { class: 'import-file-icon', 'aria-hidden': 'true', text: 'JSON' }),
          h('div', { class: 'import-file-copy' }, [
            h('div', { class: 'settings-row-title', text: '完整备份' }),
            h('div', { class: 'settings-row-note', text: '从本应用导出的 JSON 备份文件。' }),
            jsonName
          ]),
          jsonChoose,
          jsonFile
        ]));
      } else if (mode === 'csv') {
        var csvFile = h('input', {
          class: 'file-input-hidden',
          type: 'file',
          accept: '.csv,text/csv',
          'aria-label': '选择 CSV 周期表'
        });
        var csvName = h('div', { class: 'file-name', text: '尚未选择文件' });
        var csvChoose = h('button', { class: 'btn btn-block', type: 'button' }, '选择 CSV 文件');
        csvChoose.addEventListener('click', function () { csvFile.click(); });
        csvFile.addEventListener('change', function () {
          var file = csvFile.files && csvFile.files[0];
          if (!file) return;
          csvName.textContent = fileLabel(file);
          readFile(file, function (text) {
            try { importPayload(parseCsv(text)); }
            catch (e) { fail(e && e.message ? e.message : 'CSV 解析失败'); }
          });
          csvFile.value = '';
        });
        panel.appendChild(h('div', { class: 'import-file-card' }, [
          h('div', { class: 'import-file-icon', 'aria-hidden': 'true', text: 'CSV' }),
          h('div', { class: 'import-file-copy' }, [
            h('div', { class: 'settings-row-title', text: '周期表' }),
            h('div', { class: 'settings-row-note', text: '两列：开始日期、结束日期；可没有表头。' }),
            csvName
          ]),
          csvChoose,
          csvFile
        ]));
      } else {
        var today = ui.todayISO();
        var start = h('input', { class: 'field-input', type: 'date', max: today, 'aria-label': '开始日期' });
        var end = h('input', { class: 'field-input', type: 'date', min: start.value || '', max: today, 'aria-label': '结束日期' });
        var note = h('textarea', { class: 'field-input field-textarea', maxlength: '2000', placeholder: '备注（可选）', 'aria-label': '备注' });
        var save = h('button', { class: 'btn btn-primary btn-block', type: 'button' }, '新增记录');
        start.addEventListener('change', function () { end.min = start.value || ''; });
        save.addEventListener('click', function () {
          if (busy) return;
          clearError();
          if (!start.value) { fail('请选择开始日期'); return; }
          if (!ui.isValidISO(start.value) || start.value > today) { fail('开始日期不正确'); return; }
          if (end.value && (!ui.isValidISO(end.value) || end.value < start.value || end.value > today)) {
            fail('结束日期不正确');
            return;
          }
          setBusy(true);
          api.createCycle(start.value, end.value || null, note.value.trim()).then(function () {
            if (!alive) return;
            setBusy(false);
            ui.toast('已新增记录');
            end.value = '';
            note.value = '';
            clearError();
          }).catch(function (err) {
            setBusy(false);
            fail(ui.safeMessage(err, '新增失败，请检查日期'));
          });
        });
        panel.appendChild(h('div', { class: 'manual-form' }, [
          h('p', { class: 'settings-note', text: '手动补录只新增这一条，不会删除现有记录。' }),
          h('label', { class: 'field' }, [h('span', { class: 'field-label', text: '开始日期' }), start]),
          h('label', { class: 'field' }, [h('span', { class: 'field-label', text: '结束日期' }), end]),
          h('label', { class: 'field' }, [h('span', { class: 'field-label', text: '备注' }), note]),
          save
        ]));
      }
    }

    tabDefs.forEach(function (def) {
      var button = h('button', {
        class: 'import-tab',
        type: 'button',
        role: 'tab',
        'aria-selected': def.id === mode ? 'true' : 'false',
        onclick: function () { selectTab(def.id); }
      }, def.label);
      tabButtons[def.id] = button;
      tabs.appendChild(button);
    });

    container.appendChild(page('导入前建议先导出备份。', [
      h('section', { class: 'page-section' }, [
        h('div', { class: 'card danger-zone import-warning' }, [
          h('div', { class: 'settings-row-title', text: '导入是替换，不是合并' }),
          h('div', { class: 'settings-row-note', text: '导入会替换本机现有的全部周期与预测，且不可撤销。' })
        ])
      ]),
      h('section', { class: 'page-section' }, [tabs, panel, error])
    ]));
    drawPanel();
    return { destroy: function () { alive = false; } };
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.pages = window.PIQ.pages || {};
  window.PIQ.pages.data = { render: render, renderImport: renderImport };
})();
