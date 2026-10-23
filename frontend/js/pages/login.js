(function () {
  'use strict';

  var ui = window.PIQ.ui;
  var api = window.PIQ.api;
  var art = window.PIQ.art;
  var h = ui.h;

  function field(label, input, hint) {
    return h('label', { class: 'field' }, [
      h('span', { class: 'field-label', text: label }),
      input,
      hint ? h('span', { class: 'field-hint', text: hint }) : null
    ]);
  }

  function render(container) {
    var mode = 'login';
    var submitting = false;
    container.textContent = '';

    var username = h('input', {
      class: 'field-input',
      type: 'text',
      name: 'username',
      autocomplete: 'username',
      autocapitalize: 'none',
      spellcheck: 'false',
      placeholder: '用户名'
    });
    var password = h('input', {
      class: 'field-input',
      type: 'password',
      name: 'password',
      autocomplete: 'current-password',
      placeholder: '口令'
    });
    var nickname = h('input', {
      class: 'field-input',
      type: 'text',
      name: 'nickname',
      autocomplete: 'nickname',
      placeholder: '昵称（可选）'
    });
    var nicknameField = field('昵称', nickname);
    var passwordHint = h('span', { class: 'field-hint', text: '口令至少 8 位' });
    passwordHint.hidden = true;
    var error = h('div', { class: 'field-error', role: 'alert' });
    error.hidden = true;
    var submit = h('button', { class: 'btn btn-primary btn-block', type: 'submit' }, '登录');
    var switchButton = h('button', {
      class: 'link-button',
      type: 'button',
      onclick: function () {
        mode = mode === 'login' ? 'register' : 'login';
        applyMode();
      }
    }, '没有账号？注册新用户');

    var form = h('form', { class: 'login-card', novalidate: true }, [
      field('用户名', username),
      field('口令', password),
      nicknameField,
      passwordHint,
      error,
      submit
    ]);

    var page = h('div', { class: 'login-page' }, [
      h('div', { class: 'login-art-wrap' }, [
        h('img', {
          class: 'login-art',
          src: art.brandMoon,
          alt: '',
          'aria-hidden': 'true',
          width: '132',
          height: '132'
        })
      ]),
      h('div', { class: 'login-brand', text: 'PeriodIQ' }),
      h('h1', { class: 'login-title', text: '记录，然后被理解' }),
      h('p', { class: 'login-sub', text: '从第一条记录开始，模型逐步认识你的周期。' }),
      form,
      switchButton,
      h('p', { class: 'login-privacy', text: '数据仅保存在本机，不联网。' })
    ]);
    container.appendChild(page);

    function applyMode() {
      var registering = mode === 'register';
      nicknameField.hidden = !registering;
      passwordHint.hidden = !registering;
      password.setAttribute('autocomplete', registering ? 'new-password' : 'current-password');
      submit.textContent = registering ? '注册' : '登录';
      switchButton.textContent = registering ? '已有账号？登录' : '没有账号？注册新用户';
      error.hidden = true;
    }

    function fail(message) {
      error.textContent = message;
      error.hidden = false;
    }

    form.addEventListener('submit', async function (event) {
      event.preventDefault();
      if (submitting) return;
      var name = username.value.trim();
      var secret = password.value;
      var alias = nickname.value.trim();
      if (!name) { fail('请输入用户名'); username.focus(); return; }
      if (!secret) { fail('请输入口令'); password.focus(); return; }
      if (mode === 'register' && secret.length < 8) { fail('口令至少 8 位'); password.focus(); return; }
      submitting = true;
      submit.disabled = true;
      submit.textContent = mode === 'register' ? '注册中…' : '登录中…';
      error.hidden = true;
      try {
        var data = mode === 'register'
          ? await api.register(name, secret, alias || null)
          : await api.login(name, secret);
        submitting = false;
        submit.disabled = false;
        if (window.PIQ.app && typeof window.PIQ.app.onAuthed === 'function') {
          window.PIQ.app.onAuthed(data && data.user ? data.user : { username: name });
        }
      } catch (err) {
        submitting = false;
        submit.disabled = false;
        submit.textContent = mode === 'register' ? '注册' : '登录';
        if (err && err.status === 401) fail('用户名或密码不正确');
        else if (err && err.status === 409) fail('用户名已存在');
        else if (err && err.status === 400) fail(ui.safeMessage(err, mode === 'register' ? '注册失败，请检查输入' : '登录失败，请检查输入'));
        else fail(mode === 'register' ? '注册失败，请稍后重试' : '登录失败，请稍后重试');
      }
    });

    applyMode();
    setTimeout(function () { try { username.focus({ preventScroll: true }); } catch (e) { username.focus(); } }, 120);
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.pages = window.PIQ.pages || {};
  window.PIQ.pages.login = { render: render };
})();
