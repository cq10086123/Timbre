import AVFoundation
import CryptoKit
import Foundation
import UIKit

/// 给 WebDAV 书抓封面（对齐安卓：导入的书要有封面，没有就难看）：
///
/// 1. 书籍目录里的图片文件 —— cover / folder / artwork / front / 封面 优先，
///    其余按自然序取第一张（与本地导入 `AudioImporter` 同一套优先级）；
/// 2. 第一段音频的内嵌封面 —— 取文件头 2MB 写临时文件，交给 AVURLAsset 读
///    commonIdentifierArtwork（ID3 的 APIC / m4a 的 covr 都认；moov 在文件尾的
///    m4a 读不出来，8 秒超时放弃，封面是锦上添花，不能为了它把导入卡死）。
///
/// 成功后写进 `Covers/<安全化的 bookId>.<ext>`（与本地导入同一套约定），
/// 返回相对路径，由调用方 `library.setCover` 挂到书上。
enum WebDavCoverFetcher {

  static let imageExtensions: Set<String> = ["jpg", "jpeg", "png", "webp", "bmp"]
  private static let preferredNames = ["cover", "folder", "artwork", "front", "封面"]
  private static let artworkProbeBytes = 2 * 1024 * 1024
  private static let embeddedTimeoutSeconds: UInt64 = 8

  /// 返回 coverFile 相对路径；拿不到封面返回 nil（不抛错）。
  static func fetch(
    book: Book,
    server: WebDavServer,
    password: String,
    listing: [WebDavResource]?
  ) async -> String? {
    guard book.coverFile == nil || book.coverFile?.isEmpty == true else { return nil }

    // 1. 目录里的图片文件
    if let listing, let image = pickImage(in: listing) {
      if let url = URL(string: image.url),
        let data = try? await WebDavClient.get(url: url, server: server, password: password),
        let data, !data.isEmpty,
        let path = save(data, bookId: book.id, ext: ext(of: image.name))
      {
        DLog("webdav", "封面来自目录图片 \(image.name) 第\(book.title)")
        return path
      }
    }

    // 2. 第一段音频的内嵌封面
    guard let first = book.chapters.first,
      let remote = first.remoteURL,
      let url = URL(string: remote)
    else { return nil }
    guard
      let probe = try? await WebDavClient.getHead(
        url: url,
        server: server,
        password: password,
        maxBytes: artworkProbeBytes
      )
    else { return nil }

    let tmp = FileManager.default.temporaryDirectory
      .appendingPathComponent("cover-probe-\(UUID().uuidString)-\(safe(book.id)).\(probeExt(url: url))")
    if (try? probe.data.write(to: tmp, options: .atomic)) == nil { return nil }
    defer { try? FileManager.default.removeItem(at: tmp) }

    guard let artwork = await embeddedArtwork(inFileAt: tmp) else { return nil }
    guard let path = save(artwork, bookId: book.id, ext: imageExt(of: artwork)) else { return nil }
    DLog("webdav", "封面来自内嵌 artwork 第\(book.title)")
    return path
  }

  // MARK: - 目录图片挑选

  private static func pickImage(in listing: [WebDavResource]) -> WebDavResource? {
    let images = listing.filter { !$0.isDirectory && imageExtensions.contains(ext(of: $0.name)) }
    if images.isEmpty { return nil }
    for name in preferredNames {
      if let match = images.first(where: {
        ($0.name as NSString).deletingPathExtension.lowercased() == name
      }) {
        return match
      }
    }
    return images.sorted {
      NaturalSort.compare($0.name, $1.name) == .orderedAscending
    }.first
  }

  // MARK: - 内嵌封面

  private static func embeddedArtwork(inFileAt fileURL: URL) async -> Data? {
    let asset = AVURLAsset(url: fileURL)
    // 读 metadata 与 8 秒超时竞速：moov 在文件尾的 m4a 会永远读不完
    let result: Data? = await withTaskGroup(of: Data?.self) { group in
      group.addTask {
        guard let items = try? await asset.load(.metadata) else { return nil }
        guard
          let art = AVMetadataItem.metadataItems(
            from: items,
            filteredByIdentifier: .commonIdentifierArtwork
          ).first,
          let data = art.dataValue,
          !data.isEmpty
        else { return nil }
        return data
      }
      group.addTask {
        try? await Task.sleep(nanoseconds: embeddedTimeoutSeconds * 1_000_000_000)
        return nil
      }
      let first = await group.next() ?? nil
      group.cancelAll()
      return first
    }
    return result
  }

  // MARK: - 落盘

  private static func save(_ data: Data, bookId: String, ext: String) -> String? {
    let name = "\(safe(bookId)).\(ext)"
    let destination = AppPaths.coversDir.appendingPathComponent(name)
    do {
      try data.write(to: destination, options: .atomic)
      return "Covers/\(name)"
    } catch {
      DLog("webdav", "封面写盘失败：\(error.localizedDescription)")
      return nil
    }
  }

  /// bookId 里带 `/`（webdav:server:/path），当文件名必须安全化
  private static func safe(_ segment: String) -> String {
    if segment.isEmpty { return "_" }
    var out = ""
    out.reserveCapacity(segment.utf8.count)
    for byte in segment.utf8 {
      let isDigit = byte >= 48 && byte <= 57
      let isUpper = byte >= 65 && byte <= 90
      let isLower = byte >= 97 && byte <= 122
      if isDigit || isUpper || isLower || byte == 45 || byte == 46 || byte == 95 {
        out.append(Character(UnicodeScalar(byte)))
      } else {
        out += String(format: "%%%02X", Int(byte))
      }
    }
    guard out.count > 100 else { return out }
    let digest = SHA256.hash(data: Data(segment.utf8))
    return digest.map { String(format: "%02x", $0) }.joined()
  }

  private static func ext(of fileName: String) -> String {
    let raw = (fileName as NSString).pathExtension.lowercased()
    return raw.isEmpty ? "jpg" : raw
  }

  private static func probeExt(url: URL) -> String {
    let raw = url.pathExtension.lowercased()
    return ["mp3", "m4a", "m4b", "flac", "aac", "wav"].contains(raw) ? raw : "mp3"
  }

  /// 按文件魔数定扩展名：jpeg / png，认不出按 jpg
  private static func imageExt(of data: Data) -> String {
    if data.count >= 2, data[data.startIndex] == 0xFF, data[data.startIndex + 1] == 0xD8 {
      return "jpg"
    }
    if data.count >= 4, data.prefix(4) == Data([0x89, 0x50, 0x4E, 0x47]) {
      return "png"
    }
    return "jpg"
  }
}
