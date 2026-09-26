// 示例 3：防盗链音频源（带 Referer / Cookie 的两段式解析）。
// 适用：播放页才给直链、且直链必须带 Referer/UA 才能下载的站点。
// 教程见 docs/jdr-tutorial.md 第 5.3 节。

function search(keyword) {
  return api.http.request({
    url: 'https://example.com/api/search?q=' + encodeURIComponent(keyword),
    headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36' },
  }).then(function (res) {
    if (res.status !== 200) throw new Error('http ' + res.status);
    var data = JSON.parse(res.body);
    return (data.results || []).map(function (item) {
      return { id: String(item.id), title: item.title, extra: String(item.id) };
    });
  });
}

function chapters(bookId) {
  return api.http.request({
    url: 'https://example.com/api/book/' + encodeURIComponent(bookId) + '/chapters',
  }).then(function (res) {
    var data = JSON.parse(res.body);
    return (data.chapters || []).map(function (ch, i) {
      return { id: String(ch.id), title: ch.title, order: i + 1, extra: String(ch.id) };
    });
  });
}

function audio(bookId, chapterId, chapterExtra) {
  // 第一步：带 Referer 请求播放页
  return api.http.request({
    url: 'https://example.com/play/' + encodeURIComponent(chapterExtra || chapterId),
    headers: { Referer: 'https://example.com/book/' + bookId },
  }).then(function (res) {
    // 第二步：从播放页抠出真实直链
    var m = /data-src="([^"]+\.m4a[^"]*)"/.exec(res.body);
    if (!m) throw new Error('audio link not found');
    // 第三步：直链也必须带 Referer，否则 CDN 403
    // expiresAt 告诉 App 这个链接大约多久过期（可选）
    return {
      url: m[1],
      headers: { Referer: 'https://example.com/' },
      expiresAt: Date.now() + 20 * 60 * 1000,
    };
  });
}

registerSource(
  { id: 'demo-headers', name: 'Demo Headers' },
  { search: search, chapters: chapters, audio: audio },
);
