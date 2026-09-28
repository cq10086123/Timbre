// 哔哩听书(B站有声) 接口源 —— 搜索 / 章节 / 音频直链
// 与哔哩听书APP(dev.pages.abts, github.com/fengqiao57/abts)同源,底层全部是B站Web API:
//   搜索  /x/web-interface/wbi/search/type (Wbi签名)
//   章节  /x/player/pagelist               (免签名)
//   音频  /x/player/wbi/playurl            (Wbi签名, DASH)
// 关键点:
//   1. B站接口有buvid风控:脚本内自做 Cookie 预热(首页+finger/spi)+ ExClimbWuzhi 激活;
//   2. 请求必须带浏览器UA,否则412;
//   3. B站CDN校验防盗链:audio 阶段返回 {url, headers:{Referer: ...}},播放器拉流时自动带上。
// 注意:不要手动设置 Accept-Encoding,http 桥会自动 gzip。
;(function () {
  'use strict';

  var HOME = 'https://www.bilibili.com';
  var API = 'https://api.bilibili.com';
  var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
           '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

  // ---------- Wbi 签名(bilibili-API-collect 标准算法) ----------
  var MIXIN_KEY_ENC_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
    26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
    20, 34, 44, 52,
  ];

  // ---------- 听书内容过滤(与哔哩听书APP一致) ----------
  var AUDIO_HINTS = ['有声小说', '有声书', '有声剧', '有声读物', '有声', '听书', '评书', '相声',
    '广播剧', '演播', '朗读', '诵读', '长书', '连播', '说书', '单口', '播讲', '多人剧', '小说剧'];
  var NOISE_HINTS = ['一口气看完', '漫推', '漫画', '解说', '讲解', '解读', '动画', '鬼畜', '混剪',
    '沙雕', '速看', 'reaction', '预告', '游戏实况', '配音秀'];
  var GENRE_HINTS = ['评书', '相声', '广播剧', '听书', '演播', '朗读', '诵读', '长书', '连播',
    '说书', '单口', '播讲'];

  // ---------- 会话状态(进程内缓存,丢失自动重建) ----------
  var cookies = {};
  var wbiKeys = null; // { imgKey, subKey, day }
  var buvidActivated = false;
  var lastReqAt = 0;

  function sleep(ms) {
    var end = timestampMs() + ms;
    while (timestampMs() < end) {}
  }

  // 全局请求间隔,B站风控要求连续请求别太快
  function throttle() {
    var gap = timestampMs() - lastReqAt;
    if (gap < 300) sleep(300 - gap);
    lastReqAt = timestampMs();
  }

  function baseHeaders(extra) {
    var h = { 'User-Agent': UA, Referer: HOME + '/' };
    if (extra) for (var k in extra) h[k] = extra[k];
    return h;
  }

  // set-cookie 头(多个会被 http 桥用 ", " 拼接)里收割需要的 Cookie
  function harvestCookies(headers) {
    var sc = headers && headers['set-cookie'];
    if (!sc) return;
    var names = ['buvid3', 'buvid4', 'b_nut', 'SESSDATA', 'bili_jct', 'DedeUserID'];
    for (var i = 0; i < names.length; i++) {
      var m = sc.match(new RegExp('(?:^|[;,\\s])' + names[i] + '=([^;,\\s]+)'));
      if (m) cookies[names[i]] = m[1];
    }
  }

  function cookieHeader() {
    var parts = [];
    for (var k in cookies) parts.push(k + '=' + cookies[k]);
    return parts.join('; ');
  }

  // Cookie 预热:首页种 buvid3/b_nut,finger/spi 补 buvid4(缺 buvid4 时详情接口必 412)
  async function ensureSession() {
    if (cookies.buvid3 && cookies.buvid4) return;
    try {
      throttle();
      var r1 = await http.get(HOME + '/', { headers: { 'User-Agent': UA }, timeoutMs: 15000 });
      harvestCookies(r1.headers);
    } catch (e) { log('预热首页失败: ' + e); }
    if (!cookies.buvid4) {
      try {
        throttle();
        var r2 = await http.get(API + '/x/frontend/finger/spi', { headers: baseHeaders(), timeoutMs: 15000 });
        harvestCookies(r2.headers);
        var j2 = JSON.parse(r2.body);
        if (j2.code === 0 && j2.data) {
          if (j2.data.b_3) cookies.buvid3 = j2.data.b_3;
          if (j2.data.b_4) cookies.buvid4 = j2.data.b_4;
        }
      } catch (e) { log('finger/spi 失败: ' + e); }
    }
    log('会话就绪: ' + Object.keys(cookies).join(','));
  }

  // gaia 风控闭环:激活 buvid3,降低搜索触发 v_voucher 的概率(与APP同款载荷)
  async function ensureBuvidActivated() {
    if (buvidActivated || !cookies.buvid3) return;
    buvidActivated = true;
    try {
      var bytes = new Uint8Array(44);
      var r1 = randomBytes(32), r2 = randomBytes(4);
      for (var i = 0; i < 32; i++) bytes[i] = r1[i];
      var tail = [0, 0, 0, 0, 73, 69, 78, 68]; // \x00\x00\x00\x00IEND
      for (var j = 0; j < 8; j++) bytes[32 + j] = tail[j];
      for (var k = 0; k < 4; k++) bytes[40 + k] = r2[k];
      var png = base64Encode(bytes);
      var payload = JSON.stringify({
        '3064': 1,
        '39c8': '333.1387.fp.risk',
        '3c43': { adca: 'Linux', bfe9: png.slice(png.length - 50) },
      });
      throttle();
      await http.post(API + '/x/internal/gaia-gateway/ExClimbWuzhi', {
        headers: baseHeaders(),
        json: { payload: payload },
        timeoutMs: 15000,
      });
      log('buvid 已激活');
    } catch (e) { log('buvid 激活失败(不阻断): ' + e); }
  }

  function todayTag() {
    var d = new Date();
    return d.getFullYear() + '-' + d.getMonth() + '-' + d.getDate();
  }

  async function ensureWbi() {
    if (wbiKeys && wbiKeys.day === todayTag()) return wbiKeys;
    throttle();
    var r = await http.get(API + '/x/web-interface/nav', { headers: baseHeaders(), timeoutMs: 15000 });
    harvestCookies(r.headers);
    var j = JSON.parse(r.body);
    var img = j && j.data && j.data.wbi_img;
    if (!img || !img.img_url || !img.sub_url) throw new Error('获取Wbi密钥失败(HTTP ' + r.status + ')');
    wbiKeys = {
      imgKey: img.img_url.slice(img.img_url.lastIndexOf('/') + 1).split('.')[0],
      subKey: img.sub_url.slice(img.sub_url.lastIndexOf('/') + 1).split('.')[0],
      day: todayTag(),
    };
    return wbiKeys;
  }

  function getMixinKey(orig) {
    var out = '';
    for (var i = 0; i < 32; i++) out += orig.charAt(MIXIN_KEY_ENC_TAB[i]);
    return out;
  }

  // 对 params 原地追加 wts / w_rid
  function wbiSign(params, keys) {
    var mixinKey = getMixinKey(keys.imgKey + keys.subKey);
    params.wts = String(timestamp());
    var sorted = Object.keys(params).sort();
    var arr = [];
    for (var i = 0; i < sorted.length; i++) {
      var k = sorted[i], v = params[k];
      if (v === null || v === undefined) continue;
      arr.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v).replace(/[!'()*]/g, '')));
    }
    params.w_rid = md5Hex(arr.join('&') + mixinKey);
    return params;
  }

  // 通用 GET:自动预热/签名/风控码重试;返回已解析的 JSON
  async function apiGet(path, query, opts) {
    opts = opts || {};
    await ensureSession();
    await ensureBuvidActivated();
    if (opts.useWbi) query = wbiSign(query, await ensureWbi());

    var headers = baseHeaders();
    var ck = cookieHeader();
    if (ck) headers.Cookie = ck;
    if (opts.headers) for (var k in opts.headers) headers[k] = opts.headers[k];

    var retries = opts.retries || 2;
    var lastErr = null;
    for (var attempt = 0; attempt <= retries; attempt++) {
      if (attempt > 0) sleep(1000);
      throttle();
      var resp = await http.get(API + path, { headers: headers, params: query, timeoutMs: 20000 });
      if (resp.status !== 200) { lastErr = new Error('HTTP ' + resp.status); continue; }
      var body;
      try { body = JSON.parse(resp.body); } catch (e) { lastErr = e; continue; }
      if (body.code !== 0) {
        lastErr = new Error('code=' + body.code + ' ' + (body.message || ''));
        if (body.code !== -412 && body.code !== -352) break; // 非风控码不重试
        continue;
      }
      // v_voucher = 极验风控预告,换一次签名重试
      if (body.data && body.data.v_voucher && !body.data.result && attempt < retries) {
        if (opts.useWbi) delete query.w_rid, delete query.wts, wbiSign(query, wbiKeys);
        lastErr = new Error('触发极验风控');
        continue;
      }
      return body;
    }
    throw lastErr || new Error('请求失败: ' + path);
  }

  // ---------- 听书过滤 ----------
  function hasHint(list, s) {
    for (var i = 0; i < list.length; i++) if (s.indexOf(list[i]) >= 0) return true;
    return false;
  }
  function stripTags(s) { return String(s || '').replace(/<[^>]+>/g, ''); }
  function parseDuration(s) {
    if (!s) return 0;
    var p = String(s).split(':'), sec = 0;
    for (var i = 0; i < p.length; i++) sec = sec * 60 + (parseInt(p[i], 10) || 0);
    return sec;
  }
  function isAudiobook(m, keyword) {
    var title = stripTags(m.title);
    var meta = (m.description || '') + ' ' + (m.tag || '');
    if (hasHint(NOISE_HINTS, title + ' ' + meta)) return false;
    var sec = parseDuration(m.duration);
    if (hasHint(AUDIO_HINTS, title) && sec >= 300) return true;       // 标题命中且≥5分钟
    if (Number(m.typeid) === 195 && sec >= 600) return true;          // 广播剧分区且≥10分钟
    if (hasHint(AUDIO_HINTS, meta) && sec >= 1800) return true;       // 简介命中且≥30分钟
    if (hasHint(GENRE_HINTS, keyword) && sec >= 1800) return true;    // 题材词搜索且≥30分钟
    return false;
  }

  // B站搜索返回的封面是 //i0.hdslb.com/... 协议相对地址,播放器会当成本地文件路径
  function normalizePic(p) {
    var s = String(p || '');
    if (!s) return '';
    if (s.indexOf('//') === 0) return 'https:' + s;
    if (s.indexOf('http://') === 0) return 'https://' + s.slice(7);
    if (s.charAt(0) === '/') return 'https://i0.hdslb.com' + s;
    return s;
  }

  async function searchRaw(keyword, page, pageSize, order) {
    var query = {
      search_type: 'video',
      keyword: keyword,
      page: String(page || 1),
      page_size: String(pageSize || 20),
      platform: 'pc',
      web_location: '1430654',
    };
    if (order) query.order = order;
    var body = await apiGet('/x/web-interface/wbi/search/type', query, {
      useWbi: true,
      headers: {
        origin: 'https://search.bilibili.com',
        referer: 'https://search.bilibili.com/video?keyword=' + encodeURIComponent(keyword),
      },
      retries: 3,
    });
    var result = (body.data && body.data.result) || [];
    var out = [];
    for (var i = 0; i < result.length; i++) {
      var m = result[i];
      if (!m || !m.bvid || !m.aid) continue;
      if (!isAudiobook(m, keyword)) continue;
      out.push({
        id: m.bvid,
        bookTitle: stripTags(m.title),
        bookImage: normalizePic(m.pic),
        bookAnchor: m.author || '',
        bookDesc: stripTags(m.description || ''),
        // 自定义透传字段
        bvid: m.bvid,
        durationText: m.duration || '',
      });
    }
    return out;
  }

  // ---------- 三阶段 ----------
  registerSource({
    id: 'abts-bili',

    // 搜索(结果过少时用「关键词 有声小说」补搜一轮并去重,与APP逻辑一致)
    async search(params) {
      var kw = String(params.keyword || '').trim();
      if (!kw) return [];
      var primary = await searchRaw(kw, params.page || 1, params.limit || 20);
      if (primary.length >= 6 || hasHint(AUDIO_HINTS, kw)) return primary;
      try {
        var extra = await searchRaw(kw + ' 有声小说', params.page || 1, params.limit || 20);
        var seen = {};
        for (var i = 0; i < primary.length; i++) seen[primary[i].id] = true;
        for (var j = 0; j < extra.length; j++) {
          if (!seen[extra[j].id]) { seen[extra[j].id] = true; primary.push(extra[j]); }
        }
      } catch (e) { log('补搜失败(不影响主结果): ' + e); }
      return primary;
    },

    // 章节列表:一个BV号的分P列表即章节
    async chapters(params) {
      var bvid = String(params.bookId || '');
      if (!bvid) throw new Error('缺少 bookId,请从搜索结果进入');
      var body = await apiGet('/x/player/pagelist', { bvid: bvid });
      var list = body.data || [];
      var out = [];
      for (var i = 0; i < list.length; i++) {
        var c = list[i];
        if (!c || !c.cid) continue;
        out.push({
          chapter_id: String(c.cid),
          title: c.part || ('第' + c.page + '话'),
          order: c.page || (out.length + 1),
          duration: c.duration || 0,
        });
      }
      if (!out.length) throw new Error('该书没有分P章节: ' + bvid);
      return out;
    },

    // 音频直链:DASH 音轨 + Referer 请求头
    // B站CDN(upos镜像)校验防盗链:拉流必须带 Referer: https://www.bilibili.com/,
    // 否则403。契约支持 {url, headers} 后,把头随直链一起返回即可。
    async audio(params) {
      var bvid = String(params.bookId || '');
      var cid = String(params.chapterId || '');
      if (!bvid || !cid) throw new Error('缺少 bookId/chapterId');
      var body = await apiGet('/x/player/wbi/playurl', {
        bvid: bvid, cid: cid,
        qn: '80', fnval: '4048', fnver: '0', fourk: '1',
        try_look: '1', gaia_source: 'prefer-ua',
      }, { useWbi: true });
      var data = body.data || {};
      var dash = data.dash || {};
      var tracks = (dash.audio || []).slice();
      if (dash.flac && dash.flac.audio) tracks.push(dash.flac.audio);
      if (dash.dolby && dash.dolby.audio && dash.dolby.audio.length) tracks.push(dash.dolby.audio[0]);
      if (!tracks.length) throw new Error('playurl 未返回音频轨(code=' + body.code + ')');

      // 音质优先:FLAC > 杜比 > 带宽最高
      tracks.sort(function (a, b) {
        var fa = (dash.flac && a === dash.flac.audio) ? 1e12 : (a.bandwidth || 0);
        var fb = (dash.flac && b === dash.flac.audio) ? 1e12 : (b.bandwidth || 0);
        return fb - fa;
      });
      var best = tracks[0];
      var url = best.base_url || ((best.backup_url || [])[0] || '');
      if (!url) throw new Error('playurl 音频轨缺少直链');
      log('音频直链: ' + url.split('/')[2] + ' (' + best.id + ', ' + Math.round((best.bandwidth || 0) / 1000) + 'kbps)');
      // 防盗链头:播放器拉流时会自动带上(契约 {url, headers})
      return {
        url: url,
        headers: { Referer: 'https://www.bilibili.com/' },
      };
    },
  });
})();
