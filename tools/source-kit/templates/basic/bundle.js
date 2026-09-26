// Demo jdr source package: two independent sources sharing one host api.
//
// The app provides:
//   api.http.request({url, method, headers, body}) -> {status, headers, body, url}
//   api.log(...) / console.log(...)
//
// search:   array of books or {items, nextPage, hasMore}
//           book = {id, title, author?, cover?, intro?, extra?}
// chapters: [{id, title, durationSeconds?, order?, extra?}]
// audio:    a url string or {url, headers, expiresAt?}

function search(keyword) {
  return api.http.request({
    url: 'https://example.com/api/search?q=' + encodeURIComponent(keyword),
  }).then(function (res) {
    if (res.status !== 200) throw new Error('http ' + res.status);
    var data = JSON.parse(res.body);
    return (data.results || []).map(function (item) {
      return {
        id: String(item.id),
        title: item.title,
        author: item.author || '',
        cover: item.cover || '',
        intro: item.intro || '',
        extra: String(item.id),
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
        durationSeconds: ch.duration || 0,
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

registerSource({ id: 'demo-json', name: 'Demo JSON Source' }, { search: search, chapters: chapters, audio: audio });

// A second source in the same package: HTML scraping. Each source keeps its
// own id, display name and enable switch in the app.
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
    return { url: m[1], headers: { Referer: 'https://example.com/' } };
  });
}

registerSource({ id: 'demo-html', name: 'Demo HTML Source' }, {
  search: htmlSearch,
  chapters: htmlChapters,
  audio: htmlAudio,
});
