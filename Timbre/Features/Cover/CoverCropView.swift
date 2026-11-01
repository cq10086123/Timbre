import SwiftUI
import UIKit

/// 封面裁剪 —— 对齐安卓 `CropOverlay` / `EditCoverDialog`：
/// 一个正方形取景框，图片可以拖动 + 双指缩放，确认后裁成正方形封面。
struct CoverCropView: View {
  var image: UIImage
  var onCancel: () -> Void
  var onDone: (UIImage) -> Void

  @State private var scale: CGFloat = 1
  @State private var lastScale: CGFloat = 1
  @State private var offset: CGSize = .zero
  @State private var lastOffset: CGSize = .zero
  @State private var side: CGFloat = 0

  private static let minScale: CGFloat = 1
  private static let maxScale: CGFloat = 4
  /// 输出边长，够大又不至于让 library.json 那一套文件太肥
  private static let outputSide: CGFloat = 1024

  var body: some View {
    VStack(spacing: 0) {
      topBar
      Spacer(minLength: 0)
      cropArea
      Spacer(minLength: 0)
      Text("拖动调整位置，双指缩放")
        .font(AppFont.bodySmall())
        .foregroundColor(AppColor.onSurfaceVariant)
        .padding(.bottom, 24)
    }
    .background(AppColor.background.ignoresSafeArea())
  }

  private var topBar: some View {
    HStack(spacing: 0) {
      Button("取消", action: onCancel)
        .font(AppFont.labelLarge())
        .foregroundColor(AppColor.onSurfaceVariant)
        .frame(minWidth: 64, minHeight: 48, alignment: .leading)
      Spacer(minLength: 0)
      Text("裁剪封面")
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.onSurface)
      Spacer(minLength: 0)
      Button("使用") {
        if let cropped = render() { onDone(cropped) }
      }
      .font(AppFont.labelLarge())
      .foregroundColor(AppColor.primary)
      .frame(minWidth: 64, minHeight: 48, alignment: .trailing)
    }
    .padding(.horizontal, 16)
    .padding(.top, 8)
  }

  private var cropArea: some View {
    GeometryReader { geo in
      let square = min(geo.size.width, geo.size.height)
      ZStack {
        Color.black
        Image(uiImage: image)
          .resizable()
          .frame(width: displaySize(side: square).width, height: displaySize(side: square).height)
          .offset(offset)
      }
      .frame(width: square, height: square)
      .clipped()
      .contentShape(Rectangle())
      .gesture(dragGesture(side: square))
      .simultaneousGesture(magnifyGesture(side: square))
      .position(x: geo.size.width / 2, y: geo.size.height / 2)
      .onAppear { side = square }
      .onChange(of: geo.size) { _ in
        side = min(geo.size.width, geo.size.height)
      }
    }
    .aspectRatio(1, contentMode: .fit)
    .padding(.horizontal, 24)
  }

  // MARK: - 几何

  /// 图片铺满正方形所需的基准缩放（aspect fill）
  private func baseScale(side: CGFloat) -> CGFloat {
    guard side > 0, image.size.width > 0, image.size.height > 0 else { return 1 }
    return max(side / image.size.width, side / image.size.height)
  }

  private func displaySize(side: CGFloat) -> CGSize {
    let k = baseScale(side: side) * scale
    return CGSize(width: image.size.width * k, height: image.size.height * k)
  }

  private func clamp(_ value: CGSize, side: CGFloat) -> CGSize {
    let display = displaySize(side: side)
    let maxX = max(0, (display.width - side) / 2)
    let maxY = max(0, (display.height - side) / 2)
    return CGSize(
      width: min(max(-maxX, value.width), maxX),
      height: min(max(-maxY, value.height), maxY)
    )
  }

  // MARK: - 手势

  private func dragGesture(side: CGFloat) -> some Gesture {
    DragGesture()
      .onChanged { value in
        let raw = CGSize(
          width: lastOffset.width + value.translation.width,
          height: lastOffset.height + value.translation.height
        )
        offset = clamp(raw, side: side)
      }
      .onEnded { _ in lastOffset = offset }
  }

  private func magnifyGesture(side: CGFloat) -> some Gesture {
    MagnificationGesture()
      .onChanged { value in
        scale = min(Self.maxScale, max(Self.minScale, lastScale * value))
        // 放大后可用范围变大，缩小后可能越界，随时夹住
        offset = clamp(offset, side: side)
      }
      .onEnded { _ in
        lastScale = scale
        lastOffset = offset
      }
  }

  // MARK: - 出图

  private func render() -> UIImage? {
    let square = side
    guard square > 0 else { return nil }
    let output = Self.outputSide
    let k = output / square
    let display = displaySize(side: square)
    let target = CGSize(width: display.width * k, height: display.height * k)
    let origin = CGPoint(
      x: (output - target.width) / 2 + offset.width * k,
      y: (output - target.height) / 2 + offset.height * k
    )

    let format = UIGraphicsImageRendererFormat.default()
    format.scale = 1
    format.opaque = true
    let renderer = UIGraphicsImageRenderer(
      size: CGSize(width: output, height: output),
      format: format
    )
    return renderer.image { context in
      UIColor.black.setFill()
      context.fill(CGRect(x: 0, y: 0, width: output, height: output))
      image.draw(in: CGRect(origin: origin, size: target))
    }
  }
}
