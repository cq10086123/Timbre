import Foundation
import JavaScriptCore

enum SourceEngineError: LocalizedError {
  case environment
  case runtime(String)
  case source(String)

  var errorDescription: String? {
    switch self {
    case .environment: return "无法创建 JS 运行环境"
    case .runtime(let message): return message
    case .source(let message): return message
    }
  }
}

/// 用系统自带的 JavaScriptCore 做沙箱，执行 .jdr 接口源脚本。
/// 与 Android 版（QuickJS）保持同一套 API：registerSource / http / 加密桥。
enum JavaScriptSourceEngine {

  static let prelude = #"""
(function () {
  'use strict';

  var registered = null;

  globalThis.registerSource = function (source) {
    if (!source || typeof source !== 'object' || Array.isArray(source)) {
      throw new Error('registerSource(源对象) 需要一个对象参数');
    }
    if (typeof source.id !== 'string' || !source.id) {
      throw new Error('registerSource 缺少 id 字符串');
    }
    registered = source;
  };

  var hostValue = function (fn, args) {
    if (typeof fn !== 'function') throw new Error('宿主能力缺失');
    var raw = fn.apply(null, args);
    var parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (e) {
      throw new Error('宿主返回了非法 JSON');
    }
    if (parsed && typeof parsed === 'object' && parsed.error) throw new Error(parsed.error);
    if (parsed && typeof parsed === 'object' && Object.prototype.hasOwnProperty.call(parsed, 'value')) {
      return parsed.value;
    }
    return parsed;
  };

  var hexToBytes = function (hex) {
    if (typeof hex !== 'string' || hex.length % 2 !== 0) throw new Error('hexToBytes 需要偶数长度的 hex 字符串');
    var out = new Uint8Array(hex.length / 2);
    for (var i = 0; i < out.length; i++) {
      var v = parseInt(hex.substr(i * 2, 2), 16);
      if (isNaN(v)) throw new Error('hexToBytes 含非法字符');
      out[i] = v;
    }
    return out;
  };

  var bytesToHex = function (bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) {
      s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    }
    return s;
  };

  var utf8ToBytes = function (str) {
    str = String(str);
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        var lo = str.charCodeAt(i + 1);
        if (lo >= 0xdc00 && lo <= 0xdfff) {
          c = 0x10000 + ((c - 0xd800) << 10) + (lo - 0xdc00);
          i++;
        }
      }
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
      else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 0x3f), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
    return new Uint8Array(out);
  };

  var bytesToUtf8 = function (bytes) {
    var out = '';
    for (var i = 0; i < bytes.length;) {
      var b0 = bytes[i];
      var cp;
      if (b0 < 0x80) { cp = b0; i += 1; }
      else if (b0 < 0xe0) { cp = ((b0 & 0x1f) << 6) | (bytes[i + 1] & 0x3f); i += 2; }
      else if (b0 < 0xf0) { cp = ((b0 & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f); i += 3; }
      else { cp = ((b0 & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f); i += 4; }
      if (cp > 0xffff) {
        cp -= 0x10000;
        out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
      } else {
        out += String.fromCharCode(cp);
      }
    }
    return out;
  };

  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  var b64FromBytes = function (bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i += 3) {
      var b0 = bytes[i];
      var b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
      var b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
      out += B64[b0 >> 2];
      out += B64[((b0 & 3) << 4) | (b1 >> 4)];
      out += i + 1 < bytes.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=';
      out += i + 2 < bytes.length ? B64[b2 & 63] : '=';
    }
    return out;
  };

  var bytesFromB64 = function (str) {
    str = String(str).replace(/[\s=]+/g, '');
    var out = [];
    var buffer = 0;
    var bits = 0;
    for (var i = 0; i < str.length; i++) {
      var v = B64.indexOf(str[i]);
      if (v < 0) throw new Error('base64Decode 含非法字符');
      buffer = (buffer << 6) | v;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out.push((buffer >> bits) & 0xff);
      }
    }
    return new Uint8Array(out);
  };

  var toB64 = function (data) {
    return typeof data === 'string' ? b64FromBytes(utf8ToBytes(data)) : b64FromBytes(data);
  };
  var keyToB64 = toB64;

  globalThis.md5Hex = function (text) { return hostValue(__md5Hex, [String(text)]); };
  globalThis.sha256Hex = function (text) { return hostValue(__sha256Hex, [String(text)]); };
  globalThis.hmacSha256Hex = function (key, text) { return hostValue(__hmacSha256Hex, [String(key), String(text)]); };

  globalThis.base64Encode = function (data) { return toB64(data); };
  globalThis.base64Decode = function (str) { return bytesFromB64(str); };
  globalThis.base64ToUtf8 = function (str) { return bytesToUtf8(bytesFromB64(str)); };

  globalThis.hexToBytes = hexToBytes;
  globalThis.bytesToHex = bytesToHex;
  globalThis.utf8ToBytes = utf8ToBytes;
  globalThis.bytesToUtf8 = bytesToUtf8;

  globalThis.aesEcbEncryptB64 = function (data, key) {
    return hostValue(__aesEcbEncryptB64, [toB64(data), keyToB64(key)]);
  };
  globalThis.aesEcbDecrypt = function (data, key) {
    return bytesFromB64(hostValue(__aesEcbDecryptB64, [typeof data === 'string' ? data : b64FromBytes(data), keyToB64(key)]));
  };

  globalThis.aesGcmEncrypt = function (key, nonce, data) {
    return bytesFromB64(hostValue(__aesGcmEncryptB64, [keyToB64(key), b64FromBytes(nonce), toB64(data)]));
  };
  globalThis.aesGcmDecrypt = function (key, nonce, cipher) {
    return bytesFromB64(hostValue(__aesGcmDecryptB64, [keyToB64(key), b64FromBytes(nonce), typeof cipher === 'string' ? cipher : b64FromBytes(cipher)]));
  };

  globalThis.chacha20Poly1305Encrypt = function (key, nonce, data) {
    return bytesFromB64(hostValue(__chacha20Poly1305EncryptB64, [keyToB64(key), b64FromBytes(nonce), toB64(data)]));
  };
  globalThis.chacha20Poly1305Decrypt = function (key, nonce, cipher) {
    return bytesFromB64(hostValue(__chacha20Poly1305DecryptB64, [keyToB64(key), b64FromBytes(nonce), typeof cipher === 'string' ? cipher : b64FromBytes(cipher)]));
  };

  globalThis.sm4EcbEncrypt = function (data, key) {
    return bytesFromB64(hostValue(__sm4EcbEncryptB64, [toB64(data), keyToB64(key)]));
  };
  globalThis.sm4EcbDecrypt = function (data, key) {
    return bytesFromB64(hostValue(__sm4EcbDecryptB64, [typeof data === 'string' ? data : b64FromBytes(data), keyToB64(key)]));
  };

  globalThis.randomBytes = function (n) { return bytesFromB64(hostValue(__randomBytesB64, [n | 0])); };
  globalThis.timestamp = function () { return Number(__timestamp()); };
  globalThis.timestampMs = function () { return Number(__timestampMs()); };
  globalThis.urlEncode = function (text) { return hostValue(__urlEncode, [String(text)]); };
  globalThis.urlDecode = function (text) { return hostValue(__urlDecode, [String(text)]); };
  globalThis.log = function () {
    var parts = [];
    for (var i = 0; i < arguments.length; i++) parts.push(String(arguments[i]));
    try { __log(parts.join(' ')); } catch (e) {}
  };

  // 与安卓版保持一致：http.get / post 返回 Promise（宿主其实是同步请求，
  // 这里包一层 Promise 是为了让 await / .then / Promise.all 三种写法都能用）
  var doHttp = function (method, url, options) {
    var opts = options || {};
    var raw = __httpRequest(method, String(url), JSON.stringify(opts));
    var parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (e) {
      throw new Error('http 桥返回非法 JSON');
    }
    if (parsed && parsed.__error) throw new Error(parsed.__error);
    return parsed;
  };

  var httpCall = function (method) {
    return function (url, options) {
      try {
        return Promise.resolve(doHttp(method, url, options));
      } catch (e) {
        return Promise.reject(e);
      }
    };
  };

  globalThis.http = {
    get: httpCall('GET'),
    post: httpCall('POST')
  };

  globalThis.__iosStateValue = 'pending';
  globalThis.__iosValueText = '';

  globalThis.__iosStart = function (stage, paramsJson) {
    try {
      if (!registered) throw new Error('源脚本未调用 registerSource');
      var fn = registered[stage];
      if (typeof fn !== 'function') throw new Error('该源未实现 ' + stage + '()');
      var params = paramsJson ? JSON.parse(paramsJson) : {};
      Promise.resolve(fn.call(registered, params)).then(
        function (value) {
          globalThis.__iosValueText = JSON.stringify(value === undefined ? null : value);
          globalThis.__iosStateValue = 'done';
        },
        function (error) {
          globalThis.__iosValueText = String((error && error.message) || error);
          globalThis.__iosStateValue = 'error';
        }
      );
    } catch (e) {
      globalThis.__iosValueText = String((e && e.message) || e);
      globalThis.__iosStateValue = 'error';
    }
  };

  globalThis.__iosState = function () { return globalThis.__iosStateValue; };
  globalThis.__iosValue = function () { return globalThis.__iosValueText; };
})();
"""#

  /// 同步执行一个阶段，返回脚本 return 的 JSON 文本
  static func run(
    script: String,
    stage: String,
    paramsJSON: String,
    allowInsecure: Bool
  ) throws -> String {
    guard let context = JSContext() else { throw SourceEngineError.environment }
    var exceptionText: String?
    context.exceptionHandler = { _, exception in
      if let exception, !exception.isUndefined, !exception.isNull {
        exceptionText = exception.toString() ?? "未知 JS 异常"
      }
    }

    JSHostBridge.install(into: context, allowInsecure: allowInsecure)

    _ = context.evaluateScript(prelude)
    if let text = exceptionText { throw SourceEngineError.runtime("运行环境初始化失败：\(text)") }
    guard let start = context.objectForKeyedSubscript("__iosStart"), !start.isUndefined else {
      throw SourceEngineError.runtime("运行环境初始化失败：缺少 __iosStart")
    }

    _ = context.evaluateScript(script)
    if let text = exceptionText { throw SourceEngineError.source("脚本语法/初始化错误：\(text)") }

    // 关键：JSContext 的 Promise 微任务在每次 API 调用结束（栈空）时才排空，
    // 而 http 桥是同步阻塞的，所以一次 call 就能把整条 await 链跑完。
    // 这里仍然留出真实等待，兼容未来可能出现的分片异步实现。
    _ = start.call(withArguments: [stage, paramsJSON])
    if let text = exceptionText { throw SourceEngineError.source(text) }

    // JSC 的 Promise 微任务在每次 evaluateScript 返回前排空，所以轮询本身就能推进 await 链。
    // 之前只给 3 秒，一次搜索要连多个源站，真机上很容易不够，扩到 8 秒。
    let began = Date()
    var state = "pending"
    for _ in 0..<400 {
      state = context.evaluateScript("__iosState()")?.toString() ?? "pending"
      if state == "done" || state == "error" { break }
      Thread.sleep(forTimeInterval: 0.02)
    }
    let waited = Date().timeIntervalSince(began)

    let resultText = context.evaluateScript("__iosValue()")?.toString() ?? ""
    if let text = exceptionText, state != "error" {
      throw SourceEngineError.source(text)
    }
    switch state {
    case "done":
      return resultText
    case "error":
      throw SourceEngineError.source(resultText.isEmpty ? "源脚本执行失败" : resultText)
    default:
      throw SourceEngineError.source(
        "源脚本执行超时：等了 \(String(format: "%.1f", waited)) 秒仍未返回（网络可能不通）"
      )
    }
  }
}
