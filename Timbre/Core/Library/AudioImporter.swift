import AVFoundation
import Foundation

enum ImportError: LocalizedError {
  case noAudioFiles
  case copyFailed(String)

  var errorDescription: String? {
    switch self {
    case .noAudioFiles: return "没有找到可播放的音频文件（支持 MP3 / M4A / M4B / FLAC / WAV / OGG 等）"
    case .copyFailed(let name): return "拷贝失败：\(name)"
    }
  }
}

struct AudioImporter {
  static let audioExtensions: Set<String> = [
    "mp3", "m4a", "m4b", "aac", "flac", "wav", "ogg", "oga", "opus", "mkv", "webm", "3gp", "mp4",
  ]

  static let imageExtensions: Set<String> = ["jpg", "jpeg", "png", "webp", "heic", "bmp"]

  // MARK: - 导入

  /// 把一个文件夹或单个音频文件导入成一本书（会拷贝进沙盒）
  static func importItem(url: URL, settings: AppSettings) async throws -> Book {
    let accessed = url.startAccessingSecurityScopedResource()
    defer {
      if accessed { url.stopAccessingSecurityScopedResource() }
    }

    let isDirectory = (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
    var files: [URL] = []
    var rawTitle = ""

    if isDirectory {
      rawTitle = url.lastPathComponent
      files = collectAudioFiles(in: url)
    } else {
      guard isAudio(url) else { throw ImportError.noAudioFiles }
      files = [url]
      rawTitle = url.deletingPathExtension().lastPathComponent
    }
    guard !files.isEmpty else { throw ImportError.noAudioFiles }

    files.sort {
      NaturalSort.compare($0.lastPathComponent, $1.lastPathComponent) == .orderedAscending
    }

    let bookId = UUID().uuidString
    let folderRelative = "Books/\(bookId)"
    let folder = AppPaths.ensure(AppPaths.url(for: folderRelative))

    var chapters: [Chapter] = []
    for (index, file) in files.enumerated() {
      let ext = file.pathExtension.lowercased()
      let base = file.deletingPathExtension().lastPathComponent
        .replacingOccurrences(of: "/", with: "_")
      let name = String(format: "%04d_%@.%@", index + 1, base, ext)
      let destination = folder.appendingPathComponent(name)
      do {
        try FileManager.default.copyItem(at: file, to: destination)
      } catch {
        throw ImportError.copyFailed(file.lastPathComponent)
      }
      chapters.append(
        Chapter(
          id: "\(bookId)-\(index)",
          title: base,
          localFile: "\(folderRelative)/\(name)",
          duration: 0
        )
      )
    }

    // 元数据只读第一个文件，失败就用文件夹名
    var title = rawTitle
    var author = ""
    var artwork: Data?
    if let first = chapters.first?.localFile {
      let meta = await metadata(for: AppPaths.url(for: first))
      if let metaTitle = meta.title, !metaTitle.isEmpty { title = metaTitle }
      author = meta.artist ?? ""
      artwork = meta.artwork
    }

    // 封面：文件夹里的图片优先于内嵌封面
    var coverFile: String? = nil
    if isDirectory, let image = findCoverImage(in: url) {
      if let relative = copyCover(image, bookId: bookId) { coverFile = relative }
    }
    if coverFile == nil, let artwork, !artwork.isEmpty {
      let destination = AppPaths.coversDir.appendingPathComponent("\(bookId).jpg")
      if (try? artwork.write(to: destination, options: .atomic)) != nil {
        coverFile = "Covers/\(bookId).jpg"
      }
    }

    return Book(
      id: bookId,
      title: title,
      author: author,
      kind: .local,
      folder: folderRelative,
      coverFile: coverFile,
      chapters: chapters,
      addedAt: Date(),
      position: PlaybackPosition(),
      bookmarks: [],
      skipIntro: settings.defaultSkipIntro,
      skipOutro: settings.defaultSkipOutro,
      speed: settings.defaultSpeed
    )
  }

  // MARK: - 时长（后台补齐，不挡导入）

  static func fillDurations(bookId: String, chapters: [Chapter]) async -> [String: Double] {
    var durations: [String: Double] = [:]
    for chapter in chapters {
      guard let path = chapter.localFile else { continue }
      let url = AppPaths.url(for: path)
      guard FileManager.default.fileExists(atPath: url.path) else { continue }
      if let seconds = await duration(for: url), seconds > 0 {
        durations[chapter.id] = seconds
      }
    }
    return durations
  }

  static func duration(for url: URL) async -> Double? {
    let asset = AVURLAsset(url: url)
    guard let time = try? await asset.load(.duration) else { return nil }
    let seconds = time.seconds
    guard seconds.isFinite, seconds > 0 else { return nil }
    return seconds
  }

  // MARK: - 元数据

  struct Metadata {
    var title: String?
    var artist: String?
    var artwork: Data?
  }

  static func metadata(for url: URL) async -> Metadata {
    let asset = AVURLAsset(url: url)
    var result = Metadata()
    guard let items = try? await asset.load(.commonMetadata) else { return result }
    for item in items {
      guard let key = item.commonKey else { continue }
      switch key {
      case .commonIdentifierTitle:
        if let text = await loadedString(item), !text.isEmpty { result.title = text }
      case .commonIdentifierArtist:
        if let text = await loadedString(item), !text.isEmpty { result.artist = text }
      case .commonIdentifierArtwork:
        if let data = await loadedData(item), !data.isEmpty { result.artwork = data }
      default:
        continue
      }
    }
    return result
  }

  private static func loadedString(_ item: AVMetadataItem) async -> String? {
    guard let value = try? await item.load(.stringValue) else { return nil }
    return value
  }

  private static func loadedData(_ item: AVMetadataItem) async -> Data? {
    guard let value = try? await item.load(.dataValue) else { return nil }
    return value
  }

  // MARK: - 文件工具

  static func isAudio(_ url: URL) -> Bool {
    audioExtensions.contains(url.pathExtension.lowercased())
  }

  static func collectAudioFiles(in directory: URL) -> [URL] {
    var files: [URL] = []
    guard
      let enumerator = FileManager.default.enumerator(
        at: directory,
        includingPropertiesForKeys: [.isRegularFileKey],
        options: [.skipsHiddenFiles, .skipsPackageDescendants]
      )
    else { return [] }
    for case let file as URL in enumerator {
      if file.path.contains("__MACOSX") { continue }
      if isAudio(file) { files.append(file) }
    }
    return files
  }

  static func findCoverImage(in directory: URL) -> URL? {
    guard
      let contents = try? FileManager.default.contentsOfDirectory(
        at: directory,
        includingPropertiesForKeys: nil,
        options: [.skipsHiddenFiles]
      )
    else { return nil }
    let images = contents.filter { imageExtensions.contains($0.pathExtension.lowercased()) }
    if images.isEmpty { return nil }
    let preferred = ["cover", "folder", "artwork", "front", "封面"]
    for name in preferred {
      if let match = images.first(where: {
        $0.deletingPathExtension().lastPathComponent.lowercased() == name
      }) {
        return match
      }
    }
    return images.sorted {
      NaturalSort.compare($0.lastPathComponent, $1.lastPathComponent) == .orderedAscending
    }.first
  }

  private static func copyCover(_ image: URL, bookId: String) -> String? {
    let ext = image.pathExtension.lowercased().isEmpty ? "jpg" : image.pathExtension.lowercased()
    let name = "\(bookId).\(ext)"
    let destination = AppPaths.coversDir.appendingPathComponent(name)
    do {
      if FileManager.default.fileExists(atPath: destination.path) {
        try FileManager.default.removeItem(at: destination)
      }
      try FileManager.default.copyItem(at: image, to: destination)
      return "Covers/\(name)"
    } catch {
      return nil
    }
  }
}
