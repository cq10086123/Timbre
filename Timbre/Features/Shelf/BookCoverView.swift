import SwiftUI
import UIKit

/// 封面图缓存（避免列表滚动时反复解码）
enum CoverStore {
  private static let cache = NSCache<NSString, UIImage>()

  static func image(for book: Book) -> UIImage? {
    guard let cover = book.coverFile else { return nil }
    let key = cover as NSString
    if let cached = cache.object(forKey: key) { return cached }
    let url = AppPaths.url(for: cover)
    guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return nil }
    cache.setObject(image, forKey: key)
    return image
  }

  static func clear() {
    cache.removeAllObjects()
  }
}

struct BookCoverView: View {
  let book: Book
  var size: CGFloat
  var cornerRadius: CGFloat? = nil

  var body: some View {
    ZStack {
      if let image = CoverStore.image(for: book) {
        Image(uiImage: image)
          .resizable()
          .scaledToFill()
      } else {
        placeholder
      }
    }
    .frame(width: size, height: size)
    .clipShape(RoundedRectangle(cornerRadius: cornerRadius ?? size * 0.14, style: .continuous))
    .overlay(
      RoundedRectangle(cornerRadius: cornerRadius ?? size * 0.14, style: .continuous)
        .stroke(Color.white.opacity(0.06), lineWidth: 0.5)
    )
  }

  private var placeholder: some View {
    let seed = abs(book.title.hashValue % 4)
    let palettes: [[Color]] = [
      [Color(hex: 0x2B3A67), Color(hex: 0x1B2440)],
      [Color(hex: 0x3A2B5E), Color(hex: 0x231B3C)],
      [Color(hex: 0x1F3D3A), Color(hex: 0x14282A)],
      [Color(hex: 0x3F2F2A), Color(hex: 0x2A1E1B)],
    ]
    let colors = palettes[seed]
    return ZStack {
      LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
      Text(String(book.title.prefix(1)))
        .font(.system(size: size * 0.4, weight: .semibold, design: .serif))
        .foregroundColor(Color.white.opacity(0.86))
    }
  }
}

/// 源角标：本地 / 接口源名
struct SourceBadge: View {
  let book: Book

  var body: some View {
    Text(label)
      .font(.system(size: 9, weight: .medium))
      .foregroundColor(AppColor.accent)
      .padding(.horizontal, 6)
      .padding(.vertical, 2)
      .background(AppColor.accentSoft, in: Capsule())
  }

  private var label: String {
    switch book.kind {
    case .local: return "本地"
    case .jdr: return book.sourceName ?? "接口源"
    }
  }
}
