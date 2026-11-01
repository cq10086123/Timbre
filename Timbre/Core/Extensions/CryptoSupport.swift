import CommonCrypto
import CryptoKit
import Foundation

enum CryptoError: LocalizedError {
  case invalidKey
  case invalidNonce
  case sm4KeySize
  case sm4BlockSize
  case sm4Padding
  case failed(String)

  var errorDescription: String? {
    switch self {
    case .invalidKey: return "密钥长度不合法"
    case .invalidNonce: return "随机数（nonce）长度不合法"
    case .sm4KeySize: return "SM4 密钥必须是 16 字节"
    case .sm4BlockSize: return "SM4 密文必须是 16 字节的正整数倍"
    case .sm4Padding: return "SM4 填充无效"
    case .failed(let reason): return reason
    }
  }
}

/// 暴露给接口源脚本的加密原语，行为对齐 Android 版 Timbre 的 CryptoOps
enum CryptoSupport {

  // MARK: - 摘要

  static func md5(_ data: Data) -> Data {
    var digest = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
    digest.withUnsafeMutableBytes { outRaw in
      data.withUnsafeBytes { inRaw in
        _ = CC_MD5(
          inRaw.baseAddress,
          CC_LONG(data.count),
          outRaw.baseAddress?.assumingMemoryBound(to: UInt8.self)
        )
      }
    }
    return Data(digest)
  }

  static func md5Hex(_ text: String) -> String {
    md5(Data(text.utf8)).hexString
  }

  static func sha256Hex(_ text: String) -> String {
    Data(SHA256.hash(data: Data(text.utf8))).hexString
  }

  static func hmacSha256Hex(key: String, text: String) -> String {
    let keyData = Data(key.utf8)
    let symmetric = SymmetricKey(data: keyData)
    let mac = HMAC<SHA256>.authenticationCode(for: Data(text.utf8), using: symmetric)
    return Data(mac).hexString
  }

  // MARK: - AES

  /// AES/ECB/PKCS7
  static func aesEcbEncrypt(_ data: Data, key: Data) throws -> Data {
    try aesEcb(CCOperation(kCCEncrypt), data, key)
  }

  static func aesEcbDecrypt(_ data: Data, key: Data) throws -> Data {
    try aesEcb(CCOperation(kCCDecrypt), data, key)
  }

  private static func aesEcb(_ op: CCOperation, _ data: Data, _ key: Data) throws -> Data {
    guard [16, 24, 32].contains(key.count) else { throw CryptoError.invalidKey }
    var out = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
    var moved = 0
    let status: CCCryptorStatus = out.withUnsafeMutableBytes { outRaw -> CCCryptorStatus in
      key.withUnsafeBytes { keyRaw -> CCCryptorStatus in
        data.withUnsafeBytes { dataRaw -> CCCryptorStatus in
          CCCrypt(
            op,
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionPKCS7Padding | kCCOptionECBMode),
            keyRaw.baseAddress,
            key.count,
            nil,
            dataRaw.baseAddress,
            data.count,
            outRaw.baseAddress,
            outRaw.count,
            &moved
          )
        }
      }
    }
    guard status == kCCSuccess else {
      throw CryptoError.failed("AES 运算失败（状态码 \(status)）")
    }
    return Data(out[0..<moved])
  }

  /// AES-GCM，返回 ciphertext || tag
  static func aesGcmEncrypt(key: Data, nonce: Data, data: Data) throws -> Data {
    let symmetricKey = SymmetricKey(data: key)
    let gcmNonce: AES.GCM.Nonce
    do {
      gcmNonce = try AES.GCM.Nonce(data: nonce)
    } catch {
      throw CryptoError.invalidNonce
    }
    let sealed = try AES.GCM.seal(data, using: symmetricKey, nonce: gcmNonce)
    return sealed.ciphertext + sealed.tag
  }

  static func aesGcmDecrypt(key: Data, nonce: Data, combined: Data) throws -> Data {
    guard combined.count >= 16 else { throw CryptoError.failed("AES-GCM 密文过短") }
    let symmetricKey = SymmetricKey(data: key)
    let gcmNonce: AES.GCM.Nonce
    do {
      gcmNonce = try AES.GCM.Nonce(data: nonce)
    } catch {
      throw CryptoError.invalidNonce
    }
    let ciphertext = combined.prefix(combined.count - 16)
    let tag = combined.suffix(16)
    let box = try AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: ciphertext, tag: tag)
    return try AES.GCM.open(box, using: symmetricKey)
  }

  // MARK: - ChaCha20-Poly1305

  static func chachaEncrypt(key: Data, nonce: Data, data: Data) throws -> Data {
    guard nonce.count == 12 else {
      throw CryptoError.failed("iOS 版暂不支持 XChaCha20（24 字节 nonce）")
    }
    let chaNonce: ChaChaPoly.Nonce
    do {
      chaNonce = try ChaChaPoly.Nonce(data: nonce)
    } catch {
      throw CryptoError.invalidNonce
    }
    let sealed = try ChaChaPoly.seal(data, using: SymmetricKey(data: key), nonce: chaNonce)
    return sealed.ciphertext + sealed.tag
  }

  static func chachaDecrypt(key: Data, nonce: Data, combined: Data) throws -> Data {
    guard nonce.count == 12 else {
      throw CryptoError.failed("iOS 版暂不支持 XChaCha20（24 字节 nonce）")
    }
    guard combined.count >= 16 else { throw CryptoError.failed("ChaCha20 密文过短") }
    let chaNonce: ChaChaPoly.Nonce
    do {
      chaNonce = try ChaChaPoly.Nonce(data: nonce)
    } catch {
      throw CryptoError.invalidNonce
    }
    let box = try ChaChaPoly.SealedBox(
      nonce: chaNonce,
      ciphertext: combined.prefix(combined.count - 16),
      tag: combined.suffix(16)
    )
    return try ChaChaPoly.open(box, using: SymmetricKey(data: key))
  }

  // MARK: - 杂项

  static func randomBytes(_ count: Int) -> Data {
    let n = max(0, min(count, 65536))
    var generator = SystemRandomNumberGenerator()
    var bytes = [UInt8]()
    bytes.reserveCapacity(n)
    for _ in 0..<n {
      bytes.append(UInt8.random(in: 0...255, using: &generator))
    }
    return Data(bytes)
  }

  /// 与 Java URLEncoder 对齐（空格编码成 +）
  static func urlEncode(_ text: String) -> String {
    var allowed = CharacterSet(
      charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-*_ "
    )
    allowed.insert(charactersIn: " ")
    let encoded = text.addingPercentEncoding(withAllowedCharacters: allowed) ?? text
    return encoded.replacingOccurrences(of: " ", with: "+")
  }

  static func urlDecode(_ text: String) -> String {
    let replaced = text.replacingOccurrences(of: "+", with: " ")
    return replaced.removingPercentEncoding ?? replaced
  }
}

extension Data {
  var hexString: String {
    map { String(format: "%02x", $0) }.joined()
  }

  init?(hexString: String) {
    let cleaned = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
    guard cleaned.count % 2 == 0 else { return nil }
    var bytes = [UInt8]()
    bytes.reserveCapacity(cleaned.count / 2)
    var index = cleaned.startIndex
    while index < cleaned.endIndex {
      let next = cleaned.index(index, offsetBy: 2)
      guard let byte = UInt8(cleaned[index..<next], radix: 16) else { return nil }
      bytes.append(byte)
      index = next
    }
    self = Data(bytes)
  }
}
