// 示例 2：HTML 解析源。
// 适用：站点只有网页没有 JSON 接口，用正则从 HTML 里抠数据。
// 教程见 docs/jdr-tutorial.md 第 5.2 节。

function htmlSearch(keyword) {
  return api.http.request({
    url: 'https://example.com/search?q=' + encodeURIComponent(keyword),
  }).then(function (res) {
    if (res.status !== 200) throw new Error('http ' + res.status);
    var items = [];
    var re = /<a class="book" href="\/book\/(\d+)"[^>]*>([\s\S]*?)<\/a>/g;
    var m;
    while ((m = re.exec(res.body))) {
      items.push({
        id: m[1],
        title: m[2].replace(/<[^>]*>/g, '').trim(),
        extra: m[1],
      });
    }
    return items;
  });
}

function htmlChapters(bookId) {
  return api.http.request({
    url: 'https://example.com/book/' + encodeURIComponent(bookId),
  }).then(function (res) {
    var list = [];
    var re = /<li class="chapter" data-id="(\d+)"[^>]*>([^<]+)<\/li>/g;
    var m;
    while ((m = re.exec(res.body))) {
      list.push({ id: m[1], title: m[2].trim(), extra: m[1] });
    }
    return list;
  });
}

function htmlAudio(bookId, chapterId, chapterExtra) {
  return api.http.request({
    url: 'https://example.com/play/' + encodeURIComponent(chapterExtra || chapterId),
  }).then(function (res) {
    var m = /data-src="([^"]+\.mp3[^"]*)"/.exec(res.body);
    if (!m) throw new Error('audio link not found');
    return m[1];
  });
}

registerSource(
  { id: 'demo-html', name: 'Demo HTML' },
  { search: htmlSearch, chapters: htmlChapters, audio: htmlAudio },
);
