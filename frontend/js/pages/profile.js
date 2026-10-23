(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var router = window.PIQ.router;
  var h = ui.h;

  var NOTIFY_KEYS = [
    'app_settings.notify_enabled',
    'app_settings.notify_advance',
    'app_settings.notify_days',
    'app_settings.notify_on_day',
    'app_settings.notify_overdue'
  ];

  function currentUser(ctx) {
    return (window.PIQApp && window.PIQApp.user) || ctx.user || {};
  }

  function finite(value) {
    if (value === null || value === undefined || value === '') return null;
    var number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function displayName(user) {
    return String(user.nickname || user.username || '未命名');
  }

  function initial(user) {
    var text = displayName(user);
    return text ? text.slice(0, 1).toUpperCase() : '·';
  }

  function formatWeight(value) {
    var number = finite(value);
    if (number === null) return null;
    return Number.isInteger(number) ? String(number) : String(Math.round(number * 10) / 10);
  }

  function recordSince(cycles) {
    if (!Array.isArray(cycles) || !cycles.length) return '';
    var earliest = cycles.reduce(function (best, cycle) {
      if (!cycle || !cycle.period_start) return best;
      return !best || cycle.period_start < best ? cycle.period_start : best;
    }, '');
    var date = ui.parseISO(earliest);
    return date ? ui.monthTitle(date.getFullYear(), date.getMonth()) : '';
  }

  function metaText(user, cycles) {
    var parts = [];
    var age = finite(user.age);
    if (age !== null) parts.push('年龄 ' + age);
    var since = recordSince(cycles);
    if (since) parts.push('记录始于 ' + since);
    return parts.join(' · ');
  }

  function readThemeSummary() {
    var mode = window.PIQ.theme ? window.PIQ.theme.getMode() : 'system';
    if (mode === 'light') return '浅色';
    if (mode === 'dark') return '深色';
    return '跟随系统';
  }

  function readNotifySummary() {
    try {
      var enabled = api.getSetting(NOTIFY_KEYS[0]) === 'true';
      if (!enabled) return '已关闭';
      var advance = api.getSetting(NOTIFY_KEYS[1]) === 'true';
      var days = api.getSetting(NOTIFY_KEYS[2]);
      if (advance) return '已开启 · 提前 ' + (days || '3') + ' 天';
      return '已开启';
    } catch (e) {
      return '—';
    }
  }

  function row(title, summary, routeName) {
    return h('button', {
      class: 'menu-row',
      type: 'button',
      onclick: function () { router.go(routeName); }
    }, [
      h('span', { class: 'menu-label', text: title }),
      h('span', { class: 'menu-summary', text: summary }),
      h('span', { class: 'menu-chevron', 'aria-hidden': 'true', text: '›' })
    ]);
  }

  function render(container, ctx) {
    var alive = true;
    var user = currentUser(ctx);
    var name = displayName(user);
    var initialText = initial(user);
    var avatarSrc = typeof user.avatar === 'string' && /^data:image\/(png|jpeg|webp);base64,/.test(user.avatar)
      ? user.avatar : '';
    var meta = h('div', { class: 'profile-meta', text: metaText(user, null) });
    var profileCard = h('section', { class: 'profile-hero glass-focus' }, [
      h('div', { class: 'profile-avatar', 'aria-hidden': 'true' }, [
        avatarSrc
          ? h('img', { class: 'profile-avatar-img', src: avatarSrc, alt: '' })
          : h('span', { class: 'profile-avatar-mark', text: initialText })
      ]),
      h('div', { class: 'profile-copy' }, [
        h('h2', { class: 'profile-name', text: name }),
        h('div', { class: 'profile-username', text: '@' + String(user.username || '') }),
        meta
      ])
    ]);

    var edit = h('button', {
      class: 'btn btn-block profile-edit',
      type: 'button',
      onclick: function () { openEdit(user); }
    }, '编辑资料');

    var details = [];
    if (user.birthday) details.push('生日 ' + ui.fullDate(user.birthday));
    var weight = formatWeight(user.weight_kg);
    if (weight !== null) details.push('体重 ' + weight + ' kg');

    var page = h('div', { class: 'page settings-page' }, [
      h('section', { class: 'page-section' }, [
        profileCard,
        edit,
        details.length ? h('div', { class: 'profile-details', text: details.join(' · ') }) : null
      ]),
      h('section', { class: 'page-section' }, [
        h('h2', { class: 'section-title', text: '设置' }),
        h('div', { class: 'menu-list glass-surface' }, [
          row('外观', readThemeSummary(), 'settings/appearance'),
          row('通知提醒', readNotifySummary(), 'settings/notifications'),
          row('数据管理', '导出与导入', 'settings/data'),
          row('隐私与登录', '自动登录、改密、删除账号', 'settings/privacy'),
          row('关于', '版本与许可', 'settings/about')
        ])
      ])
    ]);
    container.appendChild(page);

    api.getCycles().then(function (cycles) {
      if (!alive) return;
      meta.textContent = metaText(currentUser(ctx), cycles);
    }).catch(function () { /* 记录起点不是阻塞信息 */ });

    function openEdit(baseUser) {
      var today = ui.todayISO();
      var original = {
        nickname: String(baseUser.nickname || ''),
        birthday: baseUser.birthday || '',
        weight: formatWeight(baseUser.weight_kg),
        avatar: typeof baseUser.avatar === 'string' ? baseUser.avatar : ''
      };
      var avatarData = original.avatar;
      var busy = false;
      var avatarBusy = false;
      var error = h('div', { class: 'field-error', role: 'alert' });
      error.hidden = true;
      var avatarPreview = h('div', { class: 'profile-avatar profile-avatar-edit', 'aria-hidden': 'true' }, [
        avatarData
          ? h('img', { class: 'profile-avatar-img', src: avatarData, alt: '' })
          : h('span', { class: 'profile-avatar-mark', text: initialText })
      ]);
      var avatarInput = h('input', {
        class: 'file-input-hidden',
        type: 'file',
        accept: 'image/*',
        'aria-label': '选择头像图片'
      });
      var avatarButton = h('button', {
        class: 'btn',
        type: 'button',
        onclick: function () { avatarInput.click(); }
      }, '更换头像');
      var avatarHint = h('div', { class: 'field-hint', text: '会裁成 200×200 JPEG（0.85）后保存在本机。' });

      var nicknameInput = h('input', {
        class: 'field-input',
        type: 'text',
        maxlength: '24',
        value: original.nickname,
        placeholder: '昵称'
      });
      var birthdayInput = h('input', {
        class: 'field-input',
        type: 'date',
        max: today,
        value: original.birthday
      });
      var weightInput = h('input', {
        class: 'field-input',
        type: 'number',
        min: '25',
        max: '300',
        step: '0.1',
        inputmode: 'decimal',
        value: original.weight === null ? '' : original.weight,
        placeholder: '例如 55'
      });
      var clearBirthday = h('button', {
        class: 'link-button',
        type: 'button',
        onclick: function () { birthdayInput.value = ''; }
      }, '清除');
      var clearWeight = h('button', {
        class: 'link-button',
        type: 'button',
        onclick: function () { weightInput.value = ''; }
      }, '清除');
      var save = h('button', { class: 'btn btn-primary btn-block', type: 'button' }, '保存资料');

      function fail(message) {
        error.textContent = message;
        error.hidden = false;
        try { error.scrollIntoView({ block: 'nearest' }); } catch (e) { /* 忽略 */ }
      }

      function clearError() {
        error.hidden = true;
        error.textContent = '';
      }

      function showAvatarPreview() {
        avatarPreview.textContent = '';
        if (avatarData) {
          avatarPreview.appendChild(h('img', { class: 'profile-avatar-img', src: avatarData, alt: '' }));
        } else {
          avatarPreview.appendChild(h('span', { class: 'profile-avatar-mark', text: initialText }));
        }
      }

      function processAvatar(file) {
        if (!file) return;
        if (file.type && file.type.indexOf('image/') !== 0) {
          fail('请选择图片文件');
          return;
        }
        avatarBusy = true;
        save.disabled = true;
        clearError();
        var reader = new FileReader();
        reader.onerror = function () {
          avatarBusy = false;
          save.disabled = false;
          fail('图片读取失败，请换一张');
        };
        reader.onload = function () {
          var image = new Image();
          image.onload = function () {
            var width = image.naturalWidth || image.width;
            var height = image.naturalHeight || image.height;
            if (!width || !height) {
              avatarBusy = false;
              save.disabled = false;
              fail('图片无法识别，请换一张');
              return;
            }
            var side = Math.min(width, height);
            var canvas = document.createElement('canvas');
            canvas.width = 200;
            canvas.height = 200;
            var context = canvas.getContext('2d');
            context.drawImage(
              image,
              Math.floor((width - side) / 2),
              Math.floor((height - side) / 2),
              side,
              side,
              0,
              0,
              200,
              200
            );
            var data = canvas.toDataURL('image/jpeg', 0.85);
            if (data.length > 400000) {
              avatarBusy = false;
              save.disabled = false;
              fail('图片太大，请换一张');
              return;
            }
            avatarData = data;
            avatarBusy = false;
            save.disabled = busy;
            showAvatarPreview();
          };
          image.onerror = function () {
            avatarBusy = false;
            save.disabled = false;
            fail('图片无法识别，请换一张');
          };
          image.src = String(reader.result || '');
        };
        reader.readAsDataURL(file);
      }

      avatarInput.addEventListener('change', function () {
        processAvatar(avatarInput.files && avatarInput.files[0]);
        avatarInput.value = '';
      });

      save.addEventListener('click', function () {
        if (busy || avatarBusy) return;
        clearError();
        var nickname = nicknameInput.value.trim();
        if (!nickname) { fail('昵称不能为空'); nicknameInput.focus(); return; }
        if (nickname.length > 24) { fail('昵称最多 24 个字符'); nicknameInput.focus(); return; }
        var birthday = birthdayInput.value;
        if (birthday && (!ui.isValidISO(birthday) || birthday > today)) {
          fail('生日不能晚于今天');
          birthdayInput.focus();
          return;
        }
        var weightText = weightInput.value.trim();
        var weight = null;
        if (weightText) {
          weight = Number(weightText);
          if (!Number.isFinite(weight) || weight < 25 || weight > 300) {
            fail('体重应在 25 到 300 之间');
            weightInput.focus();
            return;
          }
        }
        if (avatarData && avatarData.length > 400000) {
          fail('图片太大，请换一张');
          return;
        }
        var payload = {};
        if (nickname !== original.nickname) payload.nickname = nickname;
        if (birthday !== original.birthday) {
          if (birthday) payload.birthday = birthday;
          else if (original.birthday) payload.clear_birthday = true;
        }
        if (weight !== null) {
          if (original.weight === null || weight !== Number(original.weight)) payload.weight_kg = weight;
        } else if (original.weight !== null) {
          payload.clear_weight = true;
        }
        if (avatarData !== original.avatar) payload.avatar = avatarData;
        if (!Object.keys(payload).length) { fail('没有需要保存的修改'); return; }
        busy = true;
        save.disabled = true;
        save.textContent = '保存中…';
        api.updateProfile(payload).then(function (updated) {
          busy = false;
          save.disabled = false;
          save.textContent = '保存资料';
          if (window.PIQ.app && typeof window.PIQ.app.updateUser === 'function') {
            window.PIQ.app.updateUser(updated || {});
          }
          ui.dismissAll();
          ui.toast('资料已更新');
          if (ctx && typeof ctx.refresh === 'function') ctx.refresh();
        }).catch(function (err) {
          busy = false;
          save.disabled = false;
          save.textContent = '保存资料';
          fail(ui.safeMessage(err, '保存失败，请稍后重试'));
        });
      });

      var sheetContent = h('div', { class: 'record-form' }, [
        h('div', { class: 'profile-avatar-edit-row' }, [
          avatarPreview,
          h('div', { class: 'profile-avatar-actions' }, [avatarButton, avatarHint])
        ]),
        h('label', { class: 'field' }, [
          h('span', { class: 'field-label', text: '昵称' }),
          nicknameInput
        ]),
        h('label', { class: 'field' }, [
          h('span', { class: 'field-label', text: '生日' }),
          birthdayInput,
          h('div', { class: 'field-inline-action' }, [clearBirthday])
        ]),
        h('label', { class: 'field' }, [
          h('span', { class: 'field-label', text: '体重（kg）' }),
          weightInput,
          h('div', { class: 'field-inline-action' }, [clearWeight])
        ]),
        avatarInput,
        error,
        save
      ]);
      ui.openSheet({ title: '编辑资料', content: sheetContent });
      setTimeout(function () {
        try { nicknameInput.focus({ preventScroll: true }); } catch (e) { nicknameInput.focus(); }
      }, 80);
    }

    return {
      destroy: function () { alive = false; }
    };
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.pages = window.PIQ.pages || {};
  window.PIQ.pages.profile = { render: render };
})();
