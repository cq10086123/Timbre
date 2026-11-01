import Foundation

/// 缓存文件统一存成 `.audio` 后缀（对齐安卓 OnlineChapterFileCache），
/// 但 iOS 的 AVURLAsset 认不出这个后缀，本地播放 60ms 内直接
/// `-11828 无法打开`。安卓 ExoPlayer 只嗅探内容不看后缀，所以安卓没事。
///
/// 处理办法：播放前读文件头判断真实格式，在同一目录建一个带正确后缀的
/// **硬链接**（零拷贝、不占双倍空间），播这个别名；链接失败退回复制。
/// 缓存管理（计数 / 容量 / 清理）只认 `.audio` 原件，别名不影响统计。
enum CachedAudioAlias {

  /// 给缓存文件一个系统能认出的播放地址
  static func playableURL(for cachedURL: URL) -> URL {
    let ext = sniffedExtension(for: cachedURL)
    let alias = cachedURL.deletingPathExtension().appendingPathExtension(ext)
    let fm = FileManager.default
    if fm.fileExists(atPath: alias.path) {
      // 这一集重新下载过的话，别名还指着旧内容，按修改时间判断要不要重建
      if let aliasDate = try? alias.resourceValues(forKeys: [.contentModificationDateKey])
        .contentModificationDate,
        let sourceDate = try? cachedURL.resourceValues(forKeys: [.contentModificationDateKey])
          .contentModificationDate,
        aliasDate >= sourceDate
      {
        return alias
      }
      try? fm.removeItem(at: alias)
    }
    if (try? fm.linkItem(at: cachedURL, to: alias)) != nil { return alias }
    if (try? fm.copyItem(at: cachedURL, to: alias)) != nil { return alias }
    return cachedURL
  }

  /// 由播放地址（别名或原件）还原 `.audio` 原件，坏缓存清理用
  static func cachedFile(for playURL: URL) -> URL {
    playURL.deletingPathExtension().appendingPathExtension("audio")
  }

  /// 读文件头嗅探真实音频格式
  static func sniffedExtension(for fileURL: URL) -> String {
    guard
      let handle = try? FileHandle(forReadingFrom: fileURL),
      let head = try? handle.read(upToCount: 16),
      head.count >= 4
    else { return "mp3" }
    try? handle.close()
    let bytes = [UInt8](head)
    func tag(_ range: ClosedRange<Int>) -> String? {
      guard bytes.count > range.upperBound else { return nil }
      return String(bytes: bytes[range], encoding: .ascii)
    }
    // "ID3" 头
    if bytes[0] == 0x49, bytes[1] == 0x44, bytes[2] == 0x33 { return "mp3" }
    // MP3 / AAC 裸帧同步字
    if bytes[0] == 0xFF, bytes[1] & 0xE0 == 0xE0 {
      // ADTS 同步（0xFFF + 层数标识）是 AAC，其余 MPEG 帧当 MP3
      return bytes[1] & 0xF6 == 0xF0 ? "aac" : "mp3"
    }
    // ISO-BMFF：按 major brand 分流。
    // "M4A " 是普通 m4a；iso*/dash/mp42 是 MP4 家族 —— 分片 MP4（moof）只有
    // 「电影」解析器认得，按 .mp4 给它，别按 .m4a 交给 CoreAudio。
    if tag(4...7) == "ftyp" {
      if tag(8...11) == "M4A " { return "m4a" }
      return "mp4"
    }
    if tag(0...3) == "fLaC" { return "flac" }
    if tag(0...3) == "OggS" { return "ogg" }
    if tag(0...3) == "RIFF", tag(8...11) == "WAVE" { return "wav" }
    return "mp3"
  }
}
