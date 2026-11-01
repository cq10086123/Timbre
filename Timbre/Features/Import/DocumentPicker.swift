import SwiftUI
import UniformTypeIdentifiers

/// 系统「文件」选择器：可选文件夹（整本书）或单个音频文件
struct DocumentPicker: UIViewControllerRepresentable {
  var contentTypes: [UTType]? = nil
  var allowsMultiple: Bool = true
  var onPick: ([URL]) -> Void

  func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
    let types: [UTType]
    if let contentTypes {
      types = contentTypes
    } else {
      var list: [UTType] = [.folder]
      for ext in AudioImporter.audioExtensions.sorted() {
        if let type = UTType(filenameExtension: ext) { list.append(type) }
      }
      list.append(.audio)
      types = list
    }
    let controller = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false)
    controller.allowsMultipleSelection = allowsMultiple
    controller.shouldShowFileExtensions = true
    controller.delegate = context.coordinator
    return controller
  }

  func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

  func makeCoordinator() -> Coordinator {
    Coordinator(onPick: onPick)
  }

  final class Coordinator: NSObject, UIDocumentPickerDelegate {
    private let onPick: ([URL]) -> Void

    init(onPick: @escaping ([URL]) -> Void) {
      self.onPick = onPick
    }

    func documentPicker(
      _ controller: UIDocumentPickerViewController,
      didPickDocumentsAt urls: [URL]
    ) {
      onPick(urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {}
  }
}
