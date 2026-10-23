(function () {
  'use strict';

  var ROUTES = {
    today: { path: '/', title: '今天', main: true },
    calendar: { path: '/calendar', title: '日历', main: true },
    history: { path: '/history', title: '历史', main: true },
    analysis: { path: '/analysis', title: '分析', main: true },
    profile: { path: '/profile', title: '我的', main: true },
    'settings/appearance': { path: '/settings/appearance', title: '外观', parent: 'profile' },
    'settings/notifications': { path: '/settings/notifications', title: '通知提醒', parent: 'profile' },
    'settings/data': { path: '/settings/data', title: '数据管理', parent: 'profile' },
    'settings/data/import': { path: '/settings/data/import', title: '导入数据', parent: 'settings/data' },
    'settings/privacy': { path: '/settings/privacy', title: '隐私与登录', parent: 'profile' },
    'settings/about': { path: '/settings/about', title: '关于', parent: 'profile' },
    login: { path: '/login', title: '', shell: false }
  };
  var BY_PATH = {};
  Object.keys(ROUTES).forEach(function (name) {
    ROUTES[name].name = name;
    BY_PATH[ROUTES[name].path] = ROUTES[name];
  });

  function canonicalPath(path) {
    if (path === '/me') return '/profile';
    if (path === '/settings' || path === '/settings/') return '/profile';
    if (path === '/export') return '/settings/data';
    if (path === '/import' || path.indexOf('/import/') === 0) return '/settings/data/import';
    return path;
  }

  function parseQuery(query) {
    var params = {};
    if (!query) return params;
    query.split('&').forEach(function (pair) {
      if (!pair) return;
      var index = pair.indexOf('=');
      var key = decodeURIComponent(index >= 0 ? pair.slice(0, index) : pair);
      var value = index >= 0 ? decodeURIComponent(pair.slice(index + 1)) : '';
      params[key] = value;
    });
    return params;
  }

  function current() {
    var raw = location.hash || '#/';
    var hash = raw.charAt(0) === '#' ? raw.slice(1) : raw;
    if (!hash) hash = '/';
    var queryIndex = hash.indexOf('?');
    var query = queryIndex >= 0 ? hash.slice(queryIndex + 1) : '';
    var path = queryIndex >= 0 ? hash.slice(0, queryIndex) : hash;
    if (path.length > 1 && path.charAt(path.length - 1) === '/') path = path.slice(0, -1);
    var canonical = canonicalPath(path);
    var def = BY_PATH[canonical];
    var params = parseQuery(query);
    var cleanParams = {};
    if (def && def.name === 'calendar' && params.date && /^\d{4}-\d{2}-\d{2}$/.test(params.date)) {
      cleanParams.date = params.date;
    }
    if (!def) {
      return { name: 'notfound', path: canonical, title: '', main: false, shell: true, params: cleanParams, parent: null };
    }
    return {
      name: def.name,
      path: def.path,
      title: def.title,
      main: Boolean(def.main),
      shell: def.shell !== false,
      parent: def.parent || null,
      params: cleanParams
    };
  }

  function canonicalize(route) {
    route = route || current();
    var suffix = route.name === 'calendar' && route.params && route.params.date
      ? '?date=' + encodeURIComponent(route.params.date)
      : '';
    var target = '#' + route.path + suffix;
    if (location.hash !== target) {
      try { history.replaceState(history.state, '', target); } catch (e) { /* 忽略 */ }
    }
  }

  function go(name, params) {
    var def = ROUTES[name];
    if (!def) return;
    var suffix = '';
    if (params && params.date) suffix = '?date=' + encodeURIComponent(params.date);
    location.hash = '#' + def.path + suffix;
  }

  function replace(name, params) {
    var def = ROUTES[name];
    if (!def) return;
    var suffix = '';
    if (params && params.date) suffix = '?date=' + encodeURIComponent(params.date);
    try { history.replaceState(null, '', '#' + def.path + suffix); } catch (e) { location.hash = '#' + def.path + suffix; }
  }

  function onChange(handler) {
    window.addEventListener('hashchange', handler);
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.router = {
    routes: ROUTES,
    tabs: [
      { name: 'today', icon: 'today' },
      { name: 'calendar', icon: 'calendar' },
      { name: 'history', icon: 'history' },
      { name: 'analysis', icon: 'analysis' },
      { name: 'profile', icon: 'profile' }
    ],
    current: current,
    canonicalize: canonicalize,
    go: go,
    replace: replace,
    onChange: onChange,
    isMain: function (name) { return Boolean(ROUTES[name] && ROUTES[name].main); },
    titleOf: function (name) { return ROUTES[name] ? ROUTES[name].title : ''; }
  };
})();
