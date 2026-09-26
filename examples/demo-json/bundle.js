// 示例 1：JSON API 源。
// 适用：站点提供结构化 JSON 接口（搜索/章节/播放地址都是 JSON）。
// 教程见 docs/jdr-tutorial.md 第 5.1 节。

function search(keyword) {
  return api.http.request({
    url: 'https://example.com/api/search?q=' + encodeURIComponent(keyword),
  }).then(function (res) {
    if (res.status !== 200) throw new Error('http ' + res.status);
    var data = JSON.parse(res.body);
    return (data.results || []).map(function (item) {
      return {
        id: String(item.id),          // 必填：源内唯一
        title: item.title,            // 必填
        author: item.author || '',
        cover: item.cover || '',
        intro: item.intro || '',
        extra: String(item.id),       // 私有通道：把详情 id 带给后续调用
      };
    });
  });
}

function chapters(bookId) {
  return api.http.request({
    url: 'https://example.com/api/book/' + encodeURIComponent(bookId) + '/chapters',
  }).then(function (res) {
    if (res.status !== 200) throw new Error('http ' + res.status);
    var data = JSON.parse(res.body);
    return (data.chapters || []).map(function (ch, i) {
      return {
        id: String(ch.id),
        title: ch.title,
        durationSeconds: ch.duration || 0,  // 有就填，播放后 App 会自动校正
        order: i + 1,
        extra: String(ch.id),
      };
    });
  });
}

function audio(bookId, chapterId, chapterExtra) {
  return api.http.request({
    url: 'https://example.com/api/play?book=' + encodeURIComponent(bookId) +
      '&chapter=' + encodeURIComponent(chapterExtra || chapterId),
  }).then(function (res) {
    if (res.status !== 200) throw new Error('http ' + res.status);
    var data = JSON.parse(res.body);
    return { url: data.url, headers: data.headers || {} };
  });
}

registerSource(
  { id: 'demo-json', name: 'Demo JSON' },
  { search: search, chapters: chapters, audio: audio },
);
