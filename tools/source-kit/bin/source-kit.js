#!/usr/bin/env node
'use strict';
// source-kit: build and test jdr book source packages.
//
//   source-kit new <dir>       scaffold a package
//   source-kit pack <dir>      validate + obfuscate + write <dir>.jdr
//   source-kit test <dir>      smoke-run the bundle (search/chapters/audio)
//
// Zero dependencies; requires Node 18+.

const fs = require('fs');
const path = require('path');
const { createZip } = require('../lib/zip.js');
const { obfuscate } = require('../lib/obfuscate.js');
const { loadBundle } = require('../lib/harness.js');

const MANIFEST = 'manifest.json';
const ENTRY = 'bundle.js';
const MAX_PACKAGE_BYTES = 8 * 1024 * 1024;

function readPackage(dir) {
  const manifestPath = path.join(dir, MANIFEST);
  const bundlePath = path.join(dir, ENTRY);
  if (!fs.existsSync(manifestPath)) throw new Error(`missing ${MANIFEST} in ${dir}`);
  if (!fs.existsSync(bundlePath)) throw new Error(`missing ${ENTRY} in ${dir}`);
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const bundle = fs.readFileSync(bundlePath, 'utf8');
  if (!manifest.packageId || !/^[a-z0-9][a-z0-9-]{0,63}$/.test(manifest.packageId)) {
    throw new Error('packageId must match [a-z0-9][a-z0-9-]{0,63}');
  }
  if (!Array.isArray(manifest.sources) || manifest.sources.length === 0) {
    throw new Error('manifest.sources must list at least one source');
  }
  const ids = new Set();
  for (const s of manifest.sources) {
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(s.id)) throw new Error(`bad source id: ${s.id}`);
    if (ids.has(s.id)) throw new Error(`duplicate source id: ${s.id}`);
    ids.add(s.id);
  }
  return { manifest, bundle };
}

function cmdNew(dir) {
  if (!dir) throw new Error('usage: source-kit new <dir>');
  if (fs.existsSync(dir)) throw new Error(`${dir} already exists`);
  fs.mkdirSync(dir, { recursive: true });
  const templateDir = path.join(__dirname, '..', 'templates', 'basic');
  const manifest = JSON.parse(fs.readFileSync(path.join(templateDir, MANIFEST), 'utf8'));
  manifest.packageId = path.basename(dir).toLowerCase().replace(/[^a-z0-9-]+/g, '-');
  manifest.name = path.basename(dir);
  fs.writeFileSync(path.join(dir, MANIFEST), JSON.stringify(manifest, null, 2) + '\n');
  fs.copyFileSync(path.join(templateDir, ENTRY), path.join(dir, ENTRY));
  console.log(`created ${dir}/ - edit manifest.json and bundle.js, then run:`);
  console.log(`  source-kit test ${dir}`);
  console.log(`  source-kit pack ${dir}`);
}

function cmdPack(dir) {
  if (!dir) throw new Error('usage: source-kit pack <dir>');
  const { manifest, bundle } = readPackage(dir);
  const obfuscated = obfuscate(bundle);
  const outFlag = process.argv.indexOf('-o');
  const outPath = path.resolve(
    outFlag > -1 && process.argv[outFlag + 1]
      ? process.argv[outFlag + 1]
      : dir.replace(/\/$/, '') + '.jdr',
  );
  const zip = createZip({
    [MANIFEST]: JSON.stringify(manifest, null, 2),
    [ENTRY]: obfuscated,
  });
  if (zip.length > MAX_PACKAGE_BYTES) throw new Error('packed package too large');
  fs.writeFileSync(outPath, zip);
  console.log(`packed ${dir} -> ${outPath} (${zip.length} bytes, ${manifest.sources.length} source(s), obfuscated)`);
}

async function cmdTest(dir) {
  if (!dir) throw new Error('usage: source-kit test <dir> [keyword]');
  const { manifest, bundle } = readPackage(dir);
  const keyword = process.argv[4] || 'test';

  // offline smoke: fake http so the run never leaves the machine
  const fakeHttp = async (options) => ({
    status: 200,
    url: options.url,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ results: [{ id: '1', title: 'Fake book ' + keyword, author: 'anon' }], chapters: [{ id: 'c1', title: 'Chapter 1', duration: 300 }], url: 'https://fake/audio/c1.mp3', headers: { Referer: 'https://fake/' } }),
  });

  const handle = await loadBundle(bundle, fakeHttp);
  const declared = manifest.sources.map((s) => s.id);
  const registered = handle.sources.map((s) => s.id);
  const missing = declared.filter((id) => !registered.includes(id));
  if (missing.length) throw new Error('bundle did not register manifest sources: ' + missing.join(', '));
  console.log(`registered: ${registered.join(', ')}`);

  for (const source of handle.sources) {
    const books = await handle.call(source.id, 'search', [keyword, 1]);
    const items = Array.isArray(books) ? books : books && books.items;
    console.log(`[${source.id}] search(${keyword}) -> ${items ? items.length : 0} result(s)`);
    console.log(JSON.stringify(items, null, 2).slice(0, 600));

    const bookId = items && items[0] && items[0].id;
    if (bookId != null) {
      const chapters = await handle.call(source.id, 'chapters', [String(bookId)]);
      const list = Array.isArray(chapters) ? chapters : chapters && chapters.items;
      console.log(`[${source.id}] chapters(${bookId}) -> ${list ? list.length : 0} chapter(s)`);
      const first = list && list[0];
      if (first && first.id != null) {
        const audio = await handle.call(source.id, 'audio', [String(bookId), String(first.id), first.extra || '']);
        console.log(`[${source.id}] audio -> ${typeof audio === 'string' ? audio : audio && audio.url}`);
      }
    }
  }
  console.log('smoke test passed (offline fake http)');
}

async function main() {
  const [cmd, dir] = process.argv.slice(2);
  if (cmd === 'new') return cmdNew(dir);
  if (cmd === 'pack') return cmdPack(dir);
  if (cmd === 'test') return cmdTest(dir);
  if (!cmd) {
    console.log('usage:\n  source-kit new <dir>\n  source-kit test <dir> [keyword]\n  source-kit pack <dir> [-o out.jdr]');
    return;
  }
  throw new Error(`unknown command: ${cmd}`);
}

main().catch((e) => {
  console.error('error:', e.message);
  process.exit(1);
});
