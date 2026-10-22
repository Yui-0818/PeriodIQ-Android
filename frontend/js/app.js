(function () {
  'use strict';

  var api = window.PIQ.api;
  var ui = window.PIQ.ui;
  var router = window.PIQ.router;
  var copy = window.PIQ.copy;
  var h = ui.h;

  var els = {
    app: document.getElementById('app'),
    view: document.getElementById('view'),
    title: document.getElementById('topbar-title'),
    back: document.getElementById('topbar-back'),
    avatar: document.getElementById('avatar'),
    avatarImg: document.getElementById('avatar-img'),
    avatarMark: document.getElementById('avatar-mark'),
    tabs: document.getElementById('tab-items'),
    fab: document.getElementById('fab')
  };
  var state = {
    user: null,
    page: null,
    lastMain: 'today',
    booting: true,
    ime: false,
    savedScroll: 0
  };

  function buildTabs() {
    els.tabs.textContent = '';
    router.tabs.forEach(function (tab) {
      var label = copy.tabs[tab.name] || tab.name;
      var button = h('button', {
        class: 'tab',
        type: 'button',
        'aria-label': label,
        onclick: function () {
          if (router.current().name !== tab.name) router.go(tab.name);
        }
      }, [
        h('span', { class: 'tab-icon' }, ui.icon(tab.icon)),
        h('span', { class: 'tab-label', text: label })
      ]);
      button.dataset.tab = tab.name;
      els.tabs.appendChild(button);
    });
  }

  function updateTabs(name) {
    Array.prototype.forEach.call(els.tabs.children, function (tab) {
      if (tab.dataset.tab === name) tab.setAttribute('aria-current', 'page');
      else tab.removeAttribute('aria-current');
    });
  }

  function applyAvatar(initial) {
    var avatar = state.user && typeof state.user.avatar === 'string' ? state.user.avatar : '';
    var hasAvatar = /^data:image\/(png|jpeg|webp);base64,/.test(avatar);
    if (hasAvatar) {
      els.avatarImg.src = avatar;
      els.avatarImg.hidden = false;
      els.avatarMark.hidden = true;
    } else {
      els.avatarImg.removeAttribute('src');
      els.avatarImg.hidden = true;
      els.avatarMark.hidden = false;
      els.avatarMark.textContent = initial ? String(initial).slice(0, 1).toUpperCase() : '·';
    }
  }

  function applyShell(route) {
    var login = route.name === 'login';
    els.app.classList.toggle('login-mode', login);
    els.app.classList.toggle('subpage', !login && !route.main);
    els.back.hidden = login || route.main;
    els.avatar.hidden = login || !route.main;
    els.title.hidden = login;
    els.title.textContent = route.title || '';
    document.title = route.title ? route.title + ' · PeriodIQ' : 'PeriodIQ';
    updateTabs(login || !route.main ? '' : route.name);
    var initial = state.user && (state.user.nickname || state.user.username);
    applyAvatar(initial);
  }

  function destroyPage() {
    if (state.page && typeof state.page.destroy === 'function') {
      try { state.page.destroy(); } catch (e) { /* 页面已卸载 */ }
    }
    state.page = null;
  }

  function placeholder(route) {
    return h('div', { class: 'page' }, [
      h('div', { class: 'placeholder' }, [
        h('div', { class: 'placeholder-title', text: route.title || '' }),
        h('div', { class: 'placeholder-text', text: copy.placeholder })
      ])
    ]);
  }

  function syncApp(route) {
    if (!window.PIQApp) return;
    window.PIQApp.user = state.user;
    window.PIQApp.currentRoute = route ? route.path : '';
  }

  function showLogin() {
    destroyPage();
    ui.closeAll();
    els.view.textContent = '';
    els.app.classList.add('login-mode');
    els.app.classList.remove('subpage');
    els.back.hidden = true;
    els.avatar.hidden = true;
    els.title.hidden = true;
    els.title.textContent = '';
    document.title = 'PeriodIQ';
    updateTabs('');
    syncApp(null);
    window.PIQ.pages.login.render(els.view);
  }

  function renderRoute() {
    var route = router.current();
    if (!route || route.name === 'notfound') {
      router.replace('today');
      renderRoute();
      return;
    }
    router.canonicalize(route);
    if (route.name === 'login') {
      if (state.user) {
        router.replace('today');
        renderRoute();
        return;
      }
      showLogin();
      return;
    }
    if (!state.user) {
      router.replace('login');
      showLogin();
      return;
    }
    ui.closeAll();
    if (route.main) state.lastMain = route.name;
    destroyPage();
    applyShell(route);
    els.view.textContent = '';
    var ctx = { route: route, user: state.user, refresh: refreshCurrent };
    if (route.name === 'today' && window.PIQ.pages.today) {
      state.page = window.PIQ.pages.today.render(els.view, ctx);
    } else if (route.name === 'calendar' && window.PIQ.pages.calendar) {
      state.page = window.PIQ.pages.calendar.render(els.view, ctx);
    } else if (route.name === 'history' && window.PIQ.pages.history) {
      state.page = window.PIQ.pages.history.render(els.view, ctx);
    } else if (route.name === 'analysis' && window.PIQ.pages.analysis) {
      state.page = window.PIQ.pages.analysis.render(els.view, ctx);
    } else if (route.name === 'profile' && window.PIQ.pages.profile) {
      state.page = window.PIQ.pages.profile.render(els.view, ctx);
    } else if (route.name === 'settings/notifications' && window.PIQ.pages.settings) {
      state.page = window.PIQ.pages.settings.renderNotifications(els.view, ctx);
    } else if (route.name === 'settings/data' && window.PIQ.pages.data) {
      state.page = window.PIQ.pages.data.render(els.view, ctx);
    } else if (route.name === 'settings/data/import' && window.PIQ.pages.data) {
      state.page = window.PIQ.pages.data.renderImport(els.view, ctx);
    } else if (route.name === 'settings/privacy' && window.PIQ.pages.settings) {
      state.page = window.PIQ.pages.settings.renderPrivacy(els.view, ctx);
    } else if (route.name === 'settings/about' && window.PIQ.pages.settings) {
      state.page = window.PIQ.pages.settings.renderAbout(els.view, ctx);
    } else {
      els.view.appendChild(placeholder(route));
    }
    els.view.classList.remove('page-enter');
    void els.view.offsetWidth;
    els.view.classList.add('page-enter');
    els.app.scrollTop = 0;
    syncApp(route);
  }

  function refreshCurrent() {
    if (state.page && typeof state.page.refresh === 'function') return state.page.refresh();
    renderRoute();
    return Promise.resolve();
  }

  function goBack() {
    var route = router.current();
    if (route.name === 'profile') router.go(state.lastMain);
    else if (route.parent) router.go(route.parent);
    else router.go('today');
  }

  function openRecord(mode) {
    window.PIQ.pages.record.open({ mode: mode, onDone: refreshCurrent });
  }

  function onAuthed(user) {
    state.user = user || {};
    api.setUser(state.user);
    if (window.PIQApp) window.PIQApp.user = state.user;
    els.app.classList.remove('login-mode');
    if (router.current().name === 'login') router.go('today');
    else renderRoute();
  }

  function updateUser(user) {
    state.user = user || {};
    api.setUser(state.user);
    if (window.PIQApp) window.PIQApp.user = state.user;
    applyShell(router.current());
  }

  function resetSession(message) {
    state.user = null;
    api.setToken(null);
    api.setUser(null);
    if (window.PIQApp) window.PIQApp.user = null;
    destroyPage();
    ui.closeAll();
    router.replace('login');
    showLogin();
    if (message) ui.toast(message);
  }

  async function signOut() {
    try { await api.logout(); } catch (e) { /* 本地仍必须退出 */ }
    resetSession('已退出登录');
  }

  function setupIme() {
    var root = document.documentElement;
    var viewport = window.visualViewport;
    var open = false;
    function applyIme(next) {
      if (next === open) return;
      open = next;
      state.ime = next;
      if (open) {
        state.savedScroll = els.app.scrollTop;
        root.classList.add('ime-open');
      } else {
        root.classList.remove('ime-open');
        requestAnimationFrame(function () { els.app.scrollTop = state.savedScroll; });
      }
    }
    function fromInsets() {
      var bottom = parseFloat(getComputedStyle(root).getPropertyValue('--piq-inset-bottom')) || 0;
      var ime = bottom > 120 ? bottom : 0;
      root.style.setProperty('--piq-ime-bottom', ime ? ime + 'px' : '0px');
      var viewportOpen = viewport ? (window.innerHeight - viewport.height) > 120 : false;
      applyIme(ime > 0 || viewportOpen);
    }
    if (viewport) viewport.addEventListener('resize', fromInsets);
    window.addEventListener('resize', fromInsets);
    window.addEventListener('piq-insets-updated', fromInsets);
    fromInsets();
  }

  function wire() {
    els.back.addEventListener('click', goBack);
    els.avatar.addEventListener('click', function () { router.go('profile'); });
    els.fab.addEventListener('click', function () { openRecord(); });
    router.onChange(function () {
      if (!state.booting) renderRoute();
    });
    document.addEventListener('auth:unauthorized', function () {
      if (state.booting) return;
      state.user = null;
      if (window.PIQApp) window.PIQApp.user = null;
      ui.closeAll();
      router.replace('login');
      showLogin();
      ui.toast('登录已过期，请重新登录');
    });
    window.__piqHandleBack = function () {
      if (ui.handleBack()) return true;
      var route = router.current();
      if (route && route.name !== 'login' && !route.main) {
        goBack();
        return true;
      }
      return false;
    };
  }

  async function boot() {
    buildTabs();
    wire();
    setupIme();
    var stored = api.getUser();
    state.user = null;
    if (api.hasToken()) {
      try {
        var me = await api.me();
        state.user = me || stored;
        if (state.user) api.setUser(state.user);
      } catch (err) {
        state.user = err && err.status === 401 ? null : stored;
      }
    }
    state.booting = false;
    if (!state.user) {
      router.replace('login');
      showLogin();
      return;
    }
    var route = router.current();
    if (route.name === 'login' || route.name === 'notfound') {
      router.replace('today');
    }
    renderRoute();
  }

  window.PIQApp = {
    user: null,
    currentRoute: '',
    get route() { return router.current(); },
    refresh: refreshCurrent,
    navigate: function (name) { router.go(name); }
  };
  window.App = window.PIQApp;
  window.PIQ.app = {
    onAuthed: onAuthed,
    updateUser: updateUser,
    signOut: signOut,
    signOutLocal: function (message) { resetSession(message || ''); },
    refresh: refreshCurrent,
    openRecord: openRecord,
    goBack: goBack
  };

  boot();
})();
