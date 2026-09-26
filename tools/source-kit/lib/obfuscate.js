'use strict';
// Conservative string-safe obfuscation for jdr bundles: comment stripping,
// whitespace collapsing, local identifier renaming, and a string array with
// a char-code self-decoding bootstrap. Not strong protection - it only
// avoids leaving the original source readable, as the design doc requires.

function obfuscate(source) {
  const cleaned = stripCommentsAndCollapse(source);
  const strings = [];
  const stringLess = replaceStrings(cleaned, (value) => {
    strings.push(value);
    return '\u0000S' + (strings.length - 1) + '\u0000';
  });
  const renamed = renameLocals(stringLess);

  const encoded = strings.map((s) => {
    const codes = [];
    for (const ch of s) codes.push(ch.codePointAt(0) ^ 0x5a39);
    return codes;
  });
  const order = strings.map((_, i) => i).reverse();
  const arrayName = pickName(renamed, ['_0a', '_0b', '_0c']);
  const decoderName = pickName(renamed, ['_1a', '_1b', '_1c']);

  const table = JSON.stringify(order.map((i) => encoded[i]));
  const bootstrap =
    'var ' + decoderName + '=(function(){var t=' + table + ';' +
    "return function(i){return String.fromCodePoint.apply(String,t[i].map(function(c){return c^0x5a39;}))};})();";
  const body = renamed.replace(/\u0000S(\d+)\u0000/g, (_, i) => decoderName + '(' + i + ')');
  return bootstrap + body;
}

function pickName(code, candidates) {
  for (const name of candidates) {
    if (!new RegExp('\\b' + name + '\\b').test(code)) return name;
  }
  return '_z' + Math.random().toString(36).slice(2, 6);
}

// Single pass: removes comments, collapses whitespace outside strings,
// and hands every string literal (with escapes decoded) to [replacer].
function stripCommentsAndCollapse(source) {
  let out = '';
  let i = 0;
  const n = source.length;
  let mode = 'code';
  let hadSpace = false;
  while (i < n) {
    const ch = source[i];
    const next = i + 1 < n ? source[i + 1] : '';
    if (mode === 'code') {
      if (ch === '/' && next === '/') { mode = 'line'; i += 2; continue; }
      if (ch === '/' && next === '*') { mode = 'block'; i += 2; continue; }
      if (ch === "'" || ch === '"' || ch === '`') {
        const quote = ch;
        let j = i + 1;
        let raw = '';
        while (j < n && source[j] !== quote) {
          if (source[j] === '\\') { raw += source[j] + (source[j + 1] || ''); j += 2; continue; }
          raw += source[j];
          j++;
        }
        if (j >= n) throw new Error('unterminated string literal');
        const value = decodeEscapes(raw, quote);
        out += stringsReplacerCallback(value);
        i = j + 1;
        hadSpace = false;
        continue;
      }
      if (/\s/.test(ch)) {
        if (!hadSpace) {
          const prev = out[out.length - 1] || '';
          let k = i;
          while (k < n && /\s/.test(source[k])) k++;
          const nextChar = source[k] || '';
          if (/[A-Za-z0-9_$]/.test(prev) && /[A-Za-z0-9_$]/.test(nextChar)) out += ' ';
          hadSpace = true;
        }
        i++;
        continue;
      }
      out += ch;
      hadSpace = false;
      i++;
    } else if (mode === 'line') {
      if (ch === '\n') mode = 'code';
      i++;
    } else {
      if (ch === '*' && next === '/') { mode = 'code'; out += ' '; i += 2; } else i++;
    }
  }
  return out.trim();
}

// The string replacer is injected so strip and replace stay one pass.
let stringsReplacerCallback = (s) => JSON.stringify(s);

function replaceStrings(source, replacer) {
  const prev = stringsReplacerCallback;
  stringsReplacerCallback = replacer;
  try {
    return stripCommentsAndCollapse(source);
  } finally {
    stringsReplacerCallback = prev;
  }
}

function decodeEscapes(raw, quote) {
  let out = '';
  for (let i = 0; i < raw.length; i++) {
    if (raw[i] !== '\\') { out += raw[i]; continue; }
    const c = raw[++i];
    switch (c) {
      case 'n': out += '\n'; break;
      case 't': out += '\t'; break;
      case 'r': out += '\r'; break;
      case 'b': out += '\b'; break;
      case 'f': out += '\f'; break;
      case 'v': out += '\v'; break;
      case '0': out += '\0'; break;
      case 'x': out += String.fromCharCode(parseInt(raw.slice(i + 1, i + 3), 16)); i += 2; break;
      case 'u':
        if (raw[i + 1] === '{') {
          const end = raw.indexOf('}', i);
          out += String.fromCodePoint(parseInt(raw.slice(i + 2, end), 16));
          i = end;
        } else {
          out += String.fromCharCode(parseInt(raw.slice(i + 1, i + 5), 16));
          i += 4;
        }
        break;
      default: out += c;
    }
  }
  return out;
}

function renameLocals(code) {
  const reserved = new Set([
    'registerSource', 'api', 'console', 'JSON', 'Math', 'String', 'Number', 'Object',
    'Array', 'Date', 'RegExp', 'Promise', 'parseInt', 'parseFloat', 'isNaN',
    'encodeURIComponent', 'decodeURIComponent', 'undefined', 'null', 'true', 'false',
    'this', 'arguments', 'globalThis',
  ]);
  const declared = new Set();
  const declRe = /\b(?:function|var|let|const)\s+([A-Za-z_$][\w$]*)/g;
  let m;
  while ((m = declRe.exec(code))) {
    if (!reserved.has(m[1])) declared.add(m[1]);
  }
  let out = code;
  let idx = 0;
  for (const name of declared) {
    const newName = '_f' + idx++;
    out = out.replace(new RegExp('\\b' + name.replace(/\$/g, '\\$') + '\\b', 'g'), newName);
  }
  return out;
}

module.exports = { obfuscate };
