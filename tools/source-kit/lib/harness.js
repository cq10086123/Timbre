'use strict';
// Smoke-test harness: runs a jdr bundle in a Node vm with the same host api
// the app provides (registerSource, api.http.request, api.log), then calls
// the source methods and prints the results as json.

const vm = require('vm');

async function hostHttpRequest(options) {
  const url = options && options.url;
  if (typeof url !== 'string') throw new Error('api.http.request: url is required');
  const method = (options.method || 'GET').toUpperCase();
  const response = await fetch(url, {
    method,
    headers: options.headers || {},
    body: options.body != null ? String(options.body) : undefined,
    redirect: 'follow',
  });
  const headers = {};
  response.headers.forEach((value, name) => { headers[name] = value; });
  return {
    status: response.status,
    url: response.url,
    headers,
    body: await response.text(),
  };
}

/**
 * Loads [bundle], returns {sources, call(sourceId, method, args)}.
 * `test` mode passes a fake http layer when [fakeHttp] is provided.
 */
async function loadBundle(bundle, fakeHttp) {
  const registered = {};
  const logs = [];
  const sandbox = {
    registerSource: (meta, impl) => {
      if (!meta || typeof meta.id !== 'string') {
        throw new Error('registerSource: meta.id (string) is required');
      }
      registered[meta.id] = { meta: { id: meta.id, name: String(meta.name || meta.id) }, impl };
    },
    api: {
      log: (...args) => logs.push(args.map(String).join(' ')),
      http: { request: fakeHttp || hostHttpRequest },
    },
    console: {
      log: (...args) => logs.push(args.map(String).join(' ')),
      warn: (...args) => logs.push(args.map(String).join(' ')),
      error: (...args) => logs.push(args.map(String).join(' ')),
    },
    String, Number, Object, Array, Math, JSON, Date, RegExp, Promise,
    parseInt, parseFloat, isNaN, encodeURIComponent, decodeURIComponent,
  };
  const context = vm.createContext(sandbox);
  vm.runInContext(bundle, context, { filename: 'bundle.js' });
  return {
    sources: Object.keys(registered).map((id) => registered[id].meta),
    logs,
    async call(sourceId, method, args) {
      const entry = registered[sourceId];
      if (!entry) throw new Error('source not registered: ' + sourceId);
      const fn = entry.impl[method];
      if (typeof fn !== 'function') throw new Error('source ' + sourceId + ' does not implement ' + method);
      const result = await fn.apply(entry.impl, args);
      return result === undefined ? null : result;
    },
  };
}

module.exports = { loadBundle, hostHttpRequest };
