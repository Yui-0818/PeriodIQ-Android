(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var art = window.PIQ.art;
  var theme = window.PIQ.theme;
  var h = ui.h;

  var KEY_AUTO_LOGIN = 'app_settings.auto_login';
  var KEY_NOTIFY = 'app_settings.notify_enabled';
  var KEY_ADVANCE = 'app_settings.notify_advance';
  var KEY_DAYS = 'app_settings.notify_days';
  var KEY_ON_DAY = 'app_settings.notify_on_day';
  var KEY_OVERDUE = 'app_settings.notify_overdue';
  var DAY_VALUES = ['1', '2', '3', '5', '7'];
  /* 权限框不保证触发 visibilitychange；写入后延时回读宿主实际值。 */
  var PERMISSION_RECHECK_MS = [1200, 3000];

  function page(lead, children) {
    return h('div', { class: 'page settings-page' }, [
      lead ? h('p', { class: 'settings-lead', text: lead }) : null
    ].concat(children || []));
  }

  function switchButton(checked, label, onClick, disabled) {
    var button = h('button', {
      class: 'switch',
      type: 'button',
      role: 'switch',
      'aria-checked': checked ? 'true' : 'false',
      'aria-label': label,
      disabled: disabled,
      onclick: onClick
    });
    if (disabled) button.setAttribute('aria-disabled', 'true');
    return button;
  }

  function switchRow(title, note, control) {
    return h('div', { class: 'settings-switch-row' }, [
      h('div', { class: 'settings-row-copy' }, [
        h('div', { class: 'settings-row-title', text: title }),
        note ? h('div', { class: 'settings-row-note', text: note }) : null
      ]),
      control
    ]);
  }

  function readNotifications() {
    return {
      enabled: api.getSetting(KEY_NOTIFY) === 'true',
      advance: api.getSetting(KEY_ADVANCE) === 'true',
      days: DAY_VALUES.indexOf(api.getSetting(KEY_DAYS)) >= 0 ? api.getSetting(KEY_DAYS) : '3',
      onDay: api.getSetting(KEY_ON_DAY) === 'true',
      overdue: api.getSetting(KEY_OVERDUE) === 'true'
    };
  }

  function reasonText(code) {
    if (code === 'NO_ACTIVE_SESSION') return '当前没有登录会话，自动登录打不开';
    if (code === 'STORAGE_UNAVAILABLE') return '加密存储暂不可用，自动登录未保存';
    if (code === 'INVALID_VALUE') return '设置值不合法';
    return '设置失败，请稍后重试';
  }

  function renderNotifications(container) {
    var alive = true;
    var busy = false;
    var state = null;
    var recheckTimers = [];
    var content = h('div', { class: 'settings-stack' });
    var errorSlot = h('div', { class: 'settings-error-slot' });
    content.appendChild(errorSlot);
    var view = page('提醒是设备级偏好，只保存在本机，不进入备份或导入。', [content]);
    container.appendChild(view);

    function sync() {
      if (!state) return;
      master.setAttribute('aria-checked', state.enabled ? 'true' : 'false');
      var childrenOff = !state.enabled;
      [advanceSwitch, onDaySwitch, overdueSwitch].forEach(function (control) {
        control.disabled = childrenOff;
        control.setAttribute('aria-disabled', childrenOff ? 'true' : 'false');
      });
      advanceSwitch.setAttribute('aria-checked', !childrenOff && state.advance ? 'true' : 'false');
      onDaySwitch.setAttribute('aria-checked', !childrenOff && state.onDay ? 'true' : 'false');
      overdueSwitch.setAttribute('aria-checked', !childrenOff && state.overdue ? 'true' : 'false');
      var daysOff = childrenOff || !state.advance;
      dayButtons.forEach(function (button) {
        button.disabled = daysOff;
        button.setAttribute('aria-disabled', daysOff ? 'true' : 'false');
        button.setAttribute('aria-pressed', !daysOff && button.dataset.days === state.days ? 'true' : 'false');
      });
      daysRow.hidden = !state.advance;
    }

    function refreshFromHost() {
      if (!alive) return;
      try {
        state = readNotifications();
        errorSlot.textContent = '';
        sync();
      } catch (e) {
        ui.toast('无法读取通知设置');
      }
    }

    function schedulePermissionRecheck() {
      recheckTimers.forEach(function (timer) { clearTimeout(timer); });
      recheckTimers = PERMISSION_RECHECK_MS.map(function (delay) {
        return setTimeout(function () {
          if (alive) refreshFromHost();
        }, delay);
      });
    }

    function write(key, value) {
      if (busy || !state) return;
      busy = true;
      try {
        var result = api.setSetting(key, value);
        if (result && result.ok === false) ui.toast(reasonText(result.code));
      } catch (e) {
        ui.toast('设置失败，请稍后重试');
      }
      busy = false;
      refreshFromHost();
      if (key === KEY_NOTIFY && String(value) === 'true') schedulePermissionRecheck();
    }

    var master = switchButton(false, '通知总开关', function () {
      if (state) write(KEY_NOTIFY, !state.enabled);
    });
    var advanceSwitch = switchButton(false, '提前提醒', function () {
      if (state) write(KEY_ADVANCE, !state.advance);
    });
    var onDaySwitch = switchButton(false, '当日提醒', function () {
      if (state) write(KEY_ON_DAY, !state.onDay);
    });
    var overdueSwitch = switchButton(false, '逾期提醒', function () {
      if (state) write(KEY_OVERDUE, !state.overdue);
    });
    var dayButtons = DAY_VALUES.map(function (days) {
      var button = h('button', {
        class: 'day-option',
        type: 'button',
        dataset: { days: days },
        'aria-label': '提前 ' + days + ' 天',
        onclick: function () { write(KEY_DAYS, days); }
      }, days + ' 天');
      return button;
    });
    var daysRow = h('div', { class: 'days-options' }, dayButtons);

    content.appendChild(h('section', { class: 'page-section' }, [
      h('div', { class: 'card settings-card glass-focus' }, [
        switchRow('允许提醒', '经期前后在本机发出提醒。', master)
      ])
    ]));
    content.appendChild(h('section', { class: 'page-section' }, [
      h('h2', { class: 'section-title', text: '提醒时间' }),
      h('div', { class: 'card settings-card' }, [
        switchRow('提前提醒', '在预计经期之前提醒', advanceSwitch),
        daysRow,
        switchRow('当日提醒', '在预计开始日当天提醒', onDaySwitch),
        switchRow('逾期提醒', '预计日期已过且未记录时提醒', overdueSwitch)
      ])
    ]));
    content.appendChild(h('section', { class: 'page-section' }, [
      h('p', { class: 'settings-note', text: '通知权限由 Android 管理。打开总开关时如果拒绝权限，系统会把开关回弹为关闭。' })
    ]));

    try {
      state = readNotifications();
      sync();
    } catch (e) {
      errorSlot.textContent = '';
      errorSlot.appendChild(ui.errorCard('暂时无法读取通知设置', '重试', function () {
        errorSlot.textContent = '';
        refreshFromHost();
      }));
    }

    function onVisible() {
      if (document.visibilityState === 'visible') refreshFromHost();
    }
    function onFocus() { refreshFromHost(); }
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', onFocus);
    return {
      destroy: function () {
        alive = false;
        recheckTimers.forEach(function (timer) { clearTimeout(timer); });
        document.removeEventListener('visibilitychange', onVisible);
        window.removeEventListener('focus', onFocus);
      }
    };
  }

  function renderPrivacy(container) {
    var alive = true;
    var auto = false;
    var autoSwitch = switchButton(false, '自动登录');
    var autoNote = h('div', { class: 'settings-row-note' });
    var autoError = h('div', { class: 'field-error', role: 'alert' });
    autoError.hidden = true;
    var changeButton = h('button', { class: 'btn btn-block', type: 'button' }, '修改密码');
    var logoutButton = h('button', { class: 'btn btn-text btn-block', type: 'button' }, '退出登录');
    var deleteButton = h('button', { class: 'btn btn-danger btn-block', type: 'button' }, '删除账号');

    function readAuto() {
      auto = api.getSetting(KEY_AUTO_LOGIN) === 'true';
      autoSwitch.setAttribute('aria-checked', auto ? 'true' : 'false');
      autoNote.textContent = auto ? '已开启：本机会在下次启动时尝试恢复登录。' : '未开启：每次启动都需要输入口令。';
    }

    function applyAuto() {
      var target = !auto;
      var run = function () {
        try {
          var result = api.setSetting(KEY_AUTO_LOGIN, target);
          if (result && result.ok === false) {
            autoError.textContent = reasonText(result.code);
            autoError.hidden = false;
          } else {
            autoError.hidden = true;
          }
        } catch (e) {
          autoError.textContent = '设置失败，请稍后重试';
          autoError.hidden = false;
        }
        if (alive) {
          try { readAuto(); } catch (e) { autoError.textContent = '无法读取自动登录状态'; autoError.hidden = false; }
        }
      };
      if (!target) { run(); return; }
      ui.openDialog({
        title: '开启自动登录？',
        text: '仅用于本机个人使用；它不是安全机制；登录凭证只保存在本机，不进入备份。',
        cancelLabel: '取消',
        confirmLabel: '开启'
      }).then(function (confirmed) {
        if (confirmed) run();
      });
    }
    autoSwitch.addEventListener('click', applyAuto);

    try { readAuto(); } catch (e) {
      autoError.textContent = '无法读取自动登录状态';
      autoError.hidden = false;
    }

    var view = page('本机数据由你掌控。自动登录只影响这台设备的登录体验。', [
      h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '登录' }),
        h('div', { class: 'card settings-card glass-focus' }, [
          switchRow('自动登录', '下次启动时尝试恢复本机登录', autoSwitch),
          autoNote,
          autoError
        ]),
        changeButton,
        logoutButton
      ]),
      h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '危险操作' }),
        h('div', { class: 'card danger-zone' }, [
          h('div', { class: 'settings-row-title', text: '删除账号' }),
          h('div', { class: 'settings-row-note', text: '会删除账号、周期记录、预测和模型参数，且不可撤销。' }),
          deleteButton
        ])
      ])
    ]);
    container.appendChild(view);

    changeButton.addEventListener('click', openChangePassword);
    logoutButton.addEventListener('click', function () {
      ui.openDialog({
        title: '退出登录',
        text: '数据会留在本机，下次仍可用这个账号登录。',
        cancelLabel: '取消',
        confirmLabel: '退出'
      }).then(function (confirmed) {
        if (confirmed && window.PIQ.app) window.PIQ.app.signOut();
      });
    });
    deleteButton.addEventListener('click', openDeleteAccount);

    function openChangePassword() {
      var oldInput = h('input', { class: 'field-input', type: 'password', autocomplete: 'current-password', placeholder: '当前口令' });
      var newInput = h('input', { class: 'field-input', type: 'password', autocomplete: 'new-password', placeholder: '新口令（至少 8 位）' });
      var repeatInput = h('input', { class: 'field-input', type: 'password', autocomplete: 'new-password', placeholder: '再输一次新口令' });
      var error = h('div', { class: 'field-error', role: 'alert' });
      error.hidden = true;
      var submit = h('button', { class: 'btn btn-primary btn-block', type: 'button' }, '修改密码');
      var busy = false;
      var sheet = ui.openSheet({
        title: '修改密码',
        subtitle: '其他设备会被强制登出',
        content: [
          h('label', { class: 'field' }, [h('span', { class: 'field-label', text: '当前口令' }), oldInput]),
          h('label', { class: 'field' }, [h('span', { class: 'field-label', text: '新口令' }), newInput]),
          h('label', { class: 'field' }, [h('span', { class: 'field-label', text: '确认新口令' }), repeatInput]),
          error,
          submit
        ]
      });
      submit.addEventListener('click', function () {
        if (busy) return;
        error.hidden = true;
        var oldPassword = oldInput.value;
        var newPassword = newInput.value;
        if (!oldPassword) { error.textContent = '请输入当前口令'; error.hidden = false; return; }
        if (newPassword.length < 8) { error.textContent = '新口令至少 8 位'; error.hidden = false; return; }
        if (newPassword !== repeatInput.value) { error.textContent = '两次输入的新口令不一致'; error.hidden = false; return; }
        busy = true;
        submit.disabled = true;
        submit.textContent = '保存中…';
        api.changePassword(oldPassword, newPassword).then(function (data) {
          busy = false;
          ui.dismissAll();
          ui.toast(data && data.message ? data.message : '密码已更新');
        }).catch(function (err) {
          busy = false;
          submit.disabled = false;
          submit.textContent = '修改密码';
          error.textContent = ui.safeMessage(err, '修改失败，请检查输入');
          error.hidden = false;
        });
      });
      return sheet;
    }

    function openDeleteAccount() {
      var password = h('input', { class: 'field-input', type: 'password', autocomplete: 'current-password', placeholder: '当前口令' });
      var error = h('div', { class: 'field-error', role: 'alert' });
      error.hidden = true;
      var submit = h('button', { class: 'btn btn-danger btn-block', type: 'button' }, '永久删除账号');
      var busy = false;
      ui.openSheet({
        title: '删除账号',
        subtitle: '不可撤销，所有本机数据会一并删除',
        content: [
          h('p', { class: 'settings-danger-copy', text: '请输入当前口令确认身份。删除后账号、周期记录、预测和模型参数都会消失。' }),
          h('label', { class: 'field' }, [h('span', { class: 'field-label', text: '当前口令' }), password]),
          error,
          submit
        ]
      });
      submit.addEventListener('click', function () {
        if (busy) return;
        if (!password.value) { error.textContent = '请输入当前口令'; error.hidden = false; return; }
        error.hidden = true;
        ui.openDialog({
          title: '永久删除账号？',
          text: '此操作不可撤销，账号与全部数据都会被删除。',
          danger: true,
          cancelLabel: '取消',
          confirmLabel: '永久删除'
        }).then(function (confirmed) {
          if (!confirmed) return;
          busy = true;
          submit.disabled = true;
          submit.textContent = '删除中…';
          api.deleteAccount(password.value).then(function () {
            if (window.PIQ.app && typeof window.PIQ.app.signOutLocal === 'function') {
              window.PIQ.app.signOutLocal('账号已删除');
            }
          }).catch(function (err) {
            busy = false;
            submit.disabled = false;
            submit.textContent = '永久删除账号';
            password.value = '';
            error.textContent = ui.safeMessage(err, '删除失败，请检查口令');
            error.hidden = false;
          });
        });
      });
    }

    return {
      destroy: function () { alive = false; }
    };
  }

  function renderAppearance(container) {
    var alive = true;
    var options = [
      { id: 'system', title: '跟随系统', note: '随系统的浅色或深色设置切换', icon: 'system' },
      { id: 'light', title: '浅色', note: '近白纸色背景，适合白天', icon: 'sun' },
      { id: 'dark', title: '深色', note: '暖黑夜色，适合低光环境', icon: 'moon' }
    ];
    var buttons = options.map(function (option) {
      return h('button', {
        class: 'appearance-option',
        type: 'button',
        'aria-pressed': 'false',
        dataset: { mode: option.id },
        onclick: function () {
          if (theme) theme.setMode(option.id);
          sync();
        }
      }, [
        h('span', { class: 'appearance-icon', 'aria-hidden': 'true' }, [ui.icon(option.icon)]),
        h('span', { class: 'appearance-copy' }, [
          h('span', { class: 'appearance-title', text: option.title }),
          h('span', { class: 'appearance-note', text: option.note })
        ]),
        h('span', { class: 'appearance-check', 'aria-hidden': 'true', text: '✓' })
      ]);
    });
    var view = page('外观只影响这台设备的显示，不进入备份。', [
      h('section', { class: 'page-section' }, [
        h('div', { class: 'appearance-list' }, buttons)
      ])
    ]);
    container.appendChild(view);

    function sync() {
      if (!alive) return;
      var current = theme ? theme.getMode() : 'light';
      buttons.forEach(function (button) {
        button.setAttribute('aria-pressed', button.dataset.mode === current ? 'true' : 'false');
      });
    }
    sync();
    document.addEventListener('piq:theme', sync);
    return {
      destroy: function () {
        alive = false;
        document.removeEventListener('piq:theme', sync);
      }
    };
  }

  function renderAbout(container) {
    var alive = true;
    var version = h('div', { class: 'about-brand-version', text: '版本 —' });
    var view = page(null, [
      h('section', { class: 'page-section' }, [
        h('div', { class: 'about-brand' }, [
          h('img', {
            class: 'about-brand-art',
            src: art.brandMoon,
            alt: '',
            'aria-hidden': 'true',
            width: '86',
            height: '86'
          }),
          h('div', { class: 'about-brand-copy' }, [
            h('div', { class: 'about-brand-name', text: 'PeriodIQ' }),
            version,
            h('div', { class: 'about-brand-note', text: '本地优先的周期记录与预测工具' })
          ])
        ])
      ]),
      h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '本地优先' }),
        h('p', { class: 'settings-note', text: '数据保存在本机；没有云端同步，也不会上传到服务器。' })
      ]),
      h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '算法说明' }),
        h('p', { class: 'settings-note', text: '预测由历史周期长度统计推算；这是统计推算，不是医学诊断，也不能作为避孕依据。' })
      ]),
      h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '开源许可' }),
        h('div', { class: 'card license-card' }, [
          licenseRow('本项目', 'MIT'),
          licenseRow('Python', 'PSF'),
          licenseRow('Chaquopy', 'MIT'),
          licenseRow('AndroidX', 'Apache-2.0'),
          licenseRow('Kotlin', 'Apache-2.0')
        ])
      ])
    ]);
    container.appendChild(view);
    api.health().then(function (data) {
      if (!alive) return;
      version.textContent = '版本 ' + (data && data.version ? String(data.version) : '—');
    }).catch(function () {
      if (alive) version.textContent = '版本 —';
    });
    return { destroy: function () { alive = false; } };
  }

  function licenseRow(name, license) {
    return h('div', { class: 'license-row' }, [
      h('span', { class: 'license-name', text: name }),
      h('span', { class: 'license-value', text: license })
    ]);
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.pages = window.PIQ.pages || {};
  window.PIQ.pages.settings = {
    renderAppearance: renderAppearance,
    renderNotifications: renderNotifications,
    renderPrivacy: renderPrivacy,
    renderAbout: renderAbout
  };
})();
