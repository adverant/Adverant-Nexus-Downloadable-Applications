// ModelDownloader.swift
// Downloads the Kokoro v0.19 model files from GitHub releases on first launch.
// Shows progress to the user and extracts the tar.bz2 archive.

import Foundation
import Combine

/// Tracks the state of a model download operation.
enum DownloadState: Equatable {
    case idle
    case downloading(progress: Double)
    case extracting
    case completed
    case failed(String)

    static func == (lhs: DownloadState, rhs: DownloadState) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle), (.extracting, .extracting), (.completed, .completed):
            return true
        case (.downloading(let a), .downloading(let b)):
            return a == b
        case (.failed(let a), .failed(let b)):
            return a == b
        default:
            return false
        }
    }
}

/// Downloads and extracts the Kokoro TTS model for sherpa-onnx.
/// The model archive is ~85MB compressed, ~340MB extracted.
@MainActor
final class ModelDownloader: NSObject, ObservableObject {
    /// Current download state.
    @Published var state: DownloadState = .idle
    /// Human-readable status text.
    @Published var statusText: String = ""
    /// Download progress (0.0 to 1.0).
    @Published var progress: Double = 0.0
    /// Total bytes to download.
    @Published var totalBytes: Int64 = 0
    /// Bytes downloaded so far.
    @Published var downloadedBytes: Int64 = 0

    private var downloadTask: URLSessionDownloadTask?
    private var session: URLSession?

    /// The URL for the Kokoro v0.19 model archive on GitHub releases.
    static let modelDownloadURL = URL(
        string: "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
    )!

    /// Estimated compressed archive size in bytes (~85MB).
    static let estimatedArchiveSize: Int64 = 89_000_000

    /// Check if the model is already downloaded and extracted.
    var isModelReady: Bool {
        return TTSEngine.modelFilesExist
    }

    /// Start downloading the Kokoro model.
    func startDownload() {
        guard state != .extracting else { return }
        if case .downloading = state { return }

        state = .downloading(progress: 0)
        statusText = "Starting download..."
        progress = 0
        downloadedBytes = 0
        totalBytes = 0

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForResource = 600 // 10 minutes for large model file
        config.waitsForConnectivity = true

        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        downloadTask = session?.downloadTask(with: Self.modelDownloadURL)
        downloadTask?.resume()
    }

    /// Cancel an in-progress download.
    func cancelDownload() {
        downloadTask?.cancel()
        downloadTask = nil
        session?.invalidateAndCancel()
        session = nil
        state = .idle
        statusText = "Download cancelled"
        progress = 0
    }

    /// Extract the downloaded tar.bz2 archive to the documents directory.
    /// Uses the system `tar` command via Process (available on iOS via libarchive).
    private func extractArchive(at archivePath: URL) {
        state = .extracting
        statusText = "Extracting model files..."
        progress = 0.95

        let destinationDir = TTSEngine.modelDirectory
        let parentDir = destinationDir.deletingLastPathComponent()

        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let fm = FileManager.default

                // Remove existing directory if present (clean re-extract)
                if fm.fileExists(atPath: destinationDir.path) {
                    try fm.removeItem(at: destinationDir)
                }

                // Create parent directory
                try fm.createDirectory(at: parentDir, withIntermediateDirectories: true)

                // Use libarchive-based extraction since Process is not available on iOS.
                // We decompress bz2 first, then untar.
                try self?.extractTarBz2(archivePath: archivePath, destinationDir: parentDir)

                // Verify extraction succeeded
                let modelPath = destinationDir.appendingPathComponent("model.onnx")
                guard fm.fileExists(atPath: modelPath.path) else {
                    throw NSError(
                        domain: "ModelDownloader",
                        code: 2,
                        userInfo: [NSLocalizedDescriptionKey: "Extraction completed but model.onnx not found"]
                    )
                }

                // Clean up archive
                try? fm.removeItem(at: archivePath)

                await MainActor.run {
                    self?.state = .completed
                    self?.statusText = "Model ready"
                    self?.progress = 1.0
                }
            } catch {
                await MainActor.run {
                    self?.state = .failed("Extraction failed: \(error.localizedDescription)")
                    self?.statusText = "Extraction failed"
                    self?.progress = 0
                }
            }
        }
    }

    /// Extract a .tar.bz2 archive using Foundation's built-in decompression.
    /// iOS does not have `Process`/`NSTask`, so we use a pure-Swift tar parser
    /// with bzip2 decompression via the Compression framework.
    private func extractTarBz2(archivePath: URL, destinationDir: URL) throws {
        let compressedData = try Data(contentsOf: archivePath)

        // Step 1: Decompress bz2 using the system's compression library.
        // The Compression framework supports LZFSE, LZ4, ZLIB, and LZMA but NOT bzip2.
        // Instead, we use the lower-level BZ2 C API which IS available on iOS.
        let decompressedData = try decompressBZ2(data: compressedData)

        // Step 2: Parse the tar archive
        try extractTar(data: decompressedData, to: destinationDir)
    }

    /// Decompress bzip2 data using the BZ2 C library (available on iOS via libbz2).
    /// libbz2 is linked via OTHER_LDFLAGS = "-lbz2" in the Xcode project, and
    /// bzlib.h is included via the bridging header.
    private func decompressBZ2(data: Data) throws -> Data {
        var decompressed = Data()
        let outputChunkSize = 65536

        // Allocate a persistent output buffer on the heap
        let outputBuffer = UnsafeMutablePointer<CChar>.allocate(capacity: outputChunkSize)
        defer { outputBuffer.deallocate() }

        // Initialize the bz2 decompression stream
        var stream = bz_stream()
        stream.bzalloc = nil
        stream.bzfree = nil
        stream.opaque = nil
        stream.avail_in = 0
        stream.next_in = nil

        let initResult = BZ2_bzDecompressInit(&stream, 0, 0)
        guard initResult == BZ_OK else {
            throw NSError(
                domain: "ModelDownloader",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: "BZ2 decompression init failed with code \(initResult)"]
            )
        }

        defer {
            BZ2_bzDecompressEnd(&stream)
        }

        // Copy input data to a mutable buffer (BZ2 API requires mutable next_in)
        let inputBuffer = UnsafeMutablePointer<CChar>.allocate(capacity: data.count)
        defer { inputBuffer.deallocate() }
        data.copyBytes(to: UnsafeMutableRawBufferPointer(start: inputBuffer, count: data.count)
            .bindMemory(to: UInt8.self))

        stream.next_in = inputBuffer
        stream.avail_in = UInt32(data.count)

        // Decompress in a loop
        var finished = false
        while !finished {
            stream.next_out = outputBuffer
            stream.avail_out = UInt32(outputChunkSize)

            let ret = BZ2_bzDecompress(&stream)

            let produced = outputChunkSize - Int(stream.avail_out)
            if produced > 0 {
                decompressed.append(UnsafeBufferPointer(start: outputBuffer, count: produced))
            }

            switch ret {
            case BZ_STREAM_END:
                finished = true
            case BZ_OK:
                // If no input remains and no output was produced, we are stuck
                if stream.avail_in == 0 && produced == 0 {
                    throw NSError(
                        domain: "ModelDownloader",
                        code: 5,
                        userInfo: [NSLocalizedDescriptionKey: "BZ2 decompression stalled: no input and no output"]
                    )
                }
            default:
                throw NSError(
                    domain: "ModelDownloader",
                    code: 5,
                    userInfo: [NSLocalizedDescriptionKey: "BZ2 decompression error: \(ret)"]
                )
            }
        }

        return decompressed
    }

    /// Parse and extract a POSIX tar archive from in-memory data.
    private func extractTar(data: Data, to destinationDir: URL) throws {
        let fm = FileManager.default
        var offset = 0
        let blockSize = 512

        while offset + blockSize <= data.count {
            // Read the 512-byte header block
            let headerData = data[offset..<(offset + blockSize)]

            // Check for end-of-archive (two consecutive zero blocks)
            if headerData.allSatisfy({ $0 == 0 }) {
                break
            }

            // Parse filename (bytes 0..99)
            let nameBytes = headerData[headerData.startIndex..<(headerData.startIndex + 100)]
            let name = String(bytes: nameBytes.prefix(while: { $0 != 0 }), encoding: .utf8) ?? ""

            // Parse prefix for long paths (bytes 345..499, POSIX/ustar)
            let prefixBytes = headerData[(headerData.startIndex + 345)..<(headerData.startIndex + 500)]
            let prefix = String(bytes: prefixBytes.prefix(while: { $0 != 0 }), encoding: .utf8) ?? ""

            let fullName: String
            if prefix.isEmpty {
                fullName = name
            } else {
                fullName = prefix + "/" + name
            }

            guard !fullName.isEmpty else {
                offset += blockSize
                continue
            }

            // Parse file size (bytes 124..135, octal ASCII)
            let sizeBytes = headerData[(headerData.startIndex + 124)..<(headerData.startIndex + 136)]
            let sizeStr = String(bytes: sizeBytes.prefix(while: { $0 != 0 && $0 != 0x20 }), encoding: .ascii) ?? "0"
            let fileSize = Int(sizeStr.trimmingCharacters(in: .whitespaces), radix: 8) ?? 0

            // Parse type flag (byte 156)
            let typeFlag = headerData[headerData.startIndex + 156]

            let entryPath = destinationDir.appendingPathComponent(fullName)

            offset += blockSize

            switch typeFlag {
            case 0x35, UInt8(ascii: "5"):
                // Directory
                try fm.createDirectory(at: entryPath, withIntermediateDirectories: true)

            case 0, UInt8(ascii: "0"), UInt8(ascii: " "):
                // Regular file
                let parentDir = entryPath.deletingLastPathComponent()
                if !fm.fileExists(atPath: parentDir.path) {
                    try fm.createDirectory(at: parentDir, withIntermediateDirectories: true)
                }

                if fileSize > 0 && offset + fileSize <= data.count {
                    let fileData = data[offset..<(offset + fileSize)]
                    try fileData.write(to: entryPath)
                } else if fileSize == 0 {
                    fm.createFile(atPath: entryPath.path, contents: nil)
                }

            default:
                // Symlinks, hard links, etc. - skip
                break
            }

            // Advance past file data (rounded up to 512-byte blocks)
            if fileSize > 0 {
                let dataBlocks = (fileSize + blockSize - 1) / blockSize
                offset += dataBlocks * blockSize
            }
        }
    }

    /// Format bytes into a human-readable string.
    static func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

// MARK: - URLSessionDownloadDelegate

extension ModelDownloader: URLSessionDownloadDelegate {
    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // Move the downloaded file to a stable location before extraction
        let tempDir = FileManager.default.temporaryDirectory
        let archivePath = tempDir.appendingPathComponent("kokoro-en-v0_19.tar.bz2")

        do {
            let fm = FileManager.default
            if fm.fileExists(atPath: archivePath.path) {
                try fm.removeItem(at: archivePath)
            }
            try fm.moveItem(at: location, to: archivePath)

            Task { @MainActor in
                self.extractArchive(at: archivePath)
            }
        } catch {
            Task { @MainActor in
                self.state = .failed("Failed to save download: \(error.localizedDescription)")
                self.statusText = "Save failed"
            }
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let total = totalBytesExpectedToWrite > 0
            ? totalBytesExpectedToWrite
            : ModelDownloader.estimatedArchiveSize
        let pct = Double(totalBytesWritten) / Double(total)

        Task { @MainActor in
            self.downloadedBytes = totalBytesWritten
            self.totalBytes = total
            self.progress = min(pct, 0.95) // Reserve last 5% for extraction
            self.state = .downloading(progress: self.progress)
            self.statusText = "\(ModelDownloader.formatBytes(totalBytesWritten)) / \(ModelDownloader.formatBytes(total))"
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: (any Error)?
    ) {
        if let error = error {
            let nsError = error as NSError
            // Ignore cancellation errors
            if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
                return
            }
            Task { @MainActor in
                self.state = .failed(error.localizedDescription)
                self.statusText = "Download failed"
                self.progress = 0
            }
        }
    }
}
