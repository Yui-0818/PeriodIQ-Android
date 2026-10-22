(function () {
  'use strict';

  var TOKEN_KEY = 'menstrual_auth_token';
  var USER_KEY = 'menstrual_user';
  var BASE = '/api/v1';

  function getToken() {
    try { return localStorage.getItem(TOKEN_KEY); } catch (e) { return null; }
  }
  function setToken(token) {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token);
      else localStorage.removeItem(TOKEN_KEY);
    } catch (e) { /* 存储不可用时保持内存态 */ }
  }
  function getUser() {
    try {
      var raw = localStorage.getItem(USER_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }
  function setUser(user) {
    try {
      if (user) localStorage.setItem(USER_KEY, JSON.stringify(user));
      else localStorage.removeItem(USER_KEY);
    } catch (e) { /* 同上 */ }
  }

  function parseEnvelope(raw) {
    var env = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!env || typeof env !== 'object') throw new Error('bad envelope');
    return env;
  }

  function envelopeError(env) {
    var payload = env.json && typeof env.json === 'object' ? env.json : {};
    return {
      status: Number(env.status) || 0,
      code: payload.code || payload.error || ('HTTP_' + (Number(env.status) || 0)),
      detail: payload.detail || payload.message || '',
      payload: payload
    };
  }

  function hostBridge(method) {
    var bridge = window.PIQNative;
    if (!bridge || typeof bridge[method] !== 'function') {
      throw { status: 0, code: 'BRIDGE_UNAVAILABLE', detail: '本机服务暂不可用' };
    }
    return bridge;
  }

  async function request(method, path, body) {
    var bridge = window.PIQNative;
    if (!bridge || typeof bridge.request !== 'function') {
      throw { status: 0, code: 'BRIDGE_UNAVAILABLE', detail: '本机服务暂不可用' };
    }
    var raw;
    try {
      raw = bridge.request(method, BASE + path, body ? JSON.stringify(body) : '');
    } catch (e) {
      throw { status: 0, code: 'BRIDGE_ERROR', detail: '' };
    }
    var env;
    try {
      env = parseEnvelope(raw);
    } catch (e) {
      throw { status: 0, code: 'BAD_RESPONSE', detail: '' };
    }
    var status = Number(env.status) || 0;
    if (status >= 200 && status < 300) return env.json;
    var err = envelopeError(env);
    if (status === 401) {
      // 登录失败也会返回 401；只有清理前确实有会话时才通知整页重置。
      var hadToken = Boolean(getToken());
      setToken(null);
      setUser(null);
      if (hadToken) document.dispatchEvent(new CustomEvent('auth:unauthorized'));
    }
    throw err;
  }

  var api = {
    request: request,
    getToken: getToken,
    setToken: setToken,
    getUser: getUser,
    setUser: setUser,
    hasToken: function () { return Boolean(getToken()); },

    async login(username, password) {
      var data = await request('POST', '/auth/login', { username: username, password: password });
      if (data && data.token) setToken(data.token);
      if (data && data.user) setUser(data.user);
      return data;
    },
    async register(username, password, nickname) {
      var body = { username: username, password: password };
      if (nickname) body.nickname = nickname;
      var data = await request('POST', '/auth/register', body);
      if (data && data.token) setToken(data.token);
      if (data && data.user) setUser(data.user);
      return data;
    },
    me: function () { return request('GET', '/auth/me'); },
    updateProfile: function (body) { return request('PUT', '/auth/profile', body); },
    changePassword: function (oldPassword, newPassword) {
      return request('POST', '/auth/change-password', { old_password: oldPassword, new_password: newPassword });
    },
    async deleteAccount(password) {
      var data = await request('POST', '/auth/delete-account', { password: password });
      setToken(null);
      setUser(null);
      return data;
    },
    async logout() {
      try { await request('POST', '/auth/logout'); }
      finally { setToken(null); setUser(null); }
    },

    getSetting: function (key) {
      var bridge = hostBridge('getSetting');
      try { return String(bridge.getSetting(String(key))); }
      catch (e) { throw { status: 0, code: 'SETTING_ERROR', detail: '' }; }
    },
    setSetting: function (key, value) {
      var bridge = hostBridge('setSetting');
      var raw;
      try { raw = bridge.setSetting(String(key), String(value)); }
      catch (e) { throw { status: 0, code: 'SETTING_ERROR', detail: '' }; }
      try {
        var parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== 'object') throw new Error('bad setting response');
        return parsed;
      } catch (e) {
        throw { status: 0, code: 'BAD_SETTING_RESPONSE', detail: '' };
      }
    },

    exportData: function () { return request('GET', '/export'); },
    importData: function (payload) { return request('POST', '/import', payload); },

    getCycles: function () { return request('GET', '/cycles'); },
    createCycle: function (start, end, note) {
      var body = { period_start: start, note: note || '' };
      if (end) body.period_end = end;
      return request('POST', '/cycles', body);
    },
    updateCycle: function (id, body) { return request('PUT', '/cycles/' + encodeURIComponent(id), body); },
    deleteCycle: function (id) { return request('DELETE', '/cycles/' + encodeURIComponent(id)); },

    currentPrediction: function () { return request('GET', '/predictions/current'); },
    predictionHistory: function () { return request('GET', '/predictions/history'); },
    fertileWindow: function () { return request('GET', '/analysis/fertile-window'); },
    cycleStats: function () { return request('GET', '/analysis/cycle-stats'); },
    accuracy: function () { return request('GET', '/analysis/accuracy'); },
    health: function () { return request('GET', '/health'); }
  };

  window.PIQ = window.PIQ || {};
  window.PIQ.api = api;
})();
