// HTTPServer.swift
// A minimal, dependency-free HTTP/1.1 server built on BSD sockets.
// Designed for iOS where NIO and Process are unavailable.
// Handles concurrent requests via GCD and supports CORS for web app access.

import Foundation

/// A parsed HTTP request.
struct HTTPRequest {
    let method: String
    let path: String
    let queryParams: [String: String]
    let headers: [String: String]
    let body: Data

    /// Parse query parameters from a URL path.
    static func parseQuery(_ urlPath: String) -> (path: String, params: [String: String]) {
        let components = urlPath.split(separator: "?", maxSplits: 1)
        let path = String(components[0])
        var params: [String: String] = [:]
        if components.count > 1 {
            let queryString = String(components[1])
            for pair in queryString.split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1)
                if kv.count == 2 {
                    let key = String(kv[0]).removingPercentEncoding ?? String(kv[0])
                    let val = String(kv[1]).removingPercentEncoding ?? String(kv[1])
                    params[key] = val
                }
            }
        }
        return (path, params)
    }
}

/// An HTTP response to send back to the client.
struct HTTPResponse {
    var statusCode: Int
    var statusMessage: String
    var headers: [String: String]
    var body: Data

    init(statusCode: Int = 200, statusMessage: String = "OK",
         headers: [String: String] = [:], body: Data = Data()) {
        self.statusCode = statusCode
        self.statusMessage = statusMessage
        self.headers = headers
        self.body = body
    }

    /// Create a JSON response.
    static func json(_ object: Any, statusCode: Int = 200) -> HTTPResponse {
        do {
            let data = try JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted])
            return HTTPResponse(
                statusCode: statusCode,
                statusMessage: statusCode == 200 ? "OK" : "Error",
                headers: ["Content-Type": "application/json; charset=utf-8"],
                body: data
            )
        } catch {
            let errorBody = "{\"error\": \"JSON serialization failed\"}".data(using: .utf8) ?? Data()
            return HTTPResponse(
                statusCode: 500,
                statusMessage: "Internal Server Error",
                headers: ["Content-Type": "application/json"],
                body: errorBody
            )
        }
    }

    /// Create a binary (audio) response.
    static func binary(_ data: Data, contentType: String, statusCode: Int = 200) -> HTTPResponse {
        return HTTPResponse(
            statusCode: statusCode,
            statusMessage: "OK",
            headers: [
                "Content-Type": contentType,
                "Content-Length": "\(data.count)",
            ],
            body: data
        )
    }

    /// Create an error response.
    static func error(_ message: String, statusCode: Int = 500) -> HTTPResponse {
        return json(["error": ["message": message, "type": "server_error"]], statusCode: statusCode)
    }

    /// Serialize to raw HTTP response bytes.
    func serialize() -> Data {
        var headerLines = "HTTP/1.1 \(statusCode) \(statusMessage)\r\n"
        var allHeaders = headers
        allHeaders["Content-Length"] = "\(body.count)"
        allHeaders["Connection"] = "close"

        // CORS headers for cross-origin web app access
        allHeaders["Access-Control-Allow-Origin"] = "*"
        allHeaders["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        allHeaders["Access-Control-Allow-Headers"] = "Content-Type, Authorization"

        for (key, value) in allHeaders {
            headerLines += "\(key): \(value)\r\n"
        }
        headerLines += "\r\n"

        var data = headerLines.data(using: .utf8) ?? Data()
        data.append(body)
        return data
    }
}

/// Route handler type.
typealias RouteHandler = (HTTPRequest) async -> HTTPResponse

/// A lightweight HTTP server using BSD sockets and GCD.
final class HTTPServer {
    private var serverSocket: Int32 = -1
    private var isRunning = false
    private let acceptQueue = DispatchQueue(label: "com.prosecreator.httpserver.accept", qos: .userInitiated)
    private let handlerQueue = DispatchQueue(
        label: "com.prosecreator.httpserver.handler",
        qos: .userInitiated,
        attributes: .concurrent
    )

    /// Registered route handlers.
    private var routes: [(method: String, pathPattern: String, handler: RouteHandler)] = []

    /// The port the server is listening on.
    private(set) var port: UInt16 = 0

    /// Register a GET route handler.
    func get(_ path: String, handler: @escaping RouteHandler) {
        routes.append((method: "GET", pathPattern: path, handler: handler))
    }

    /// Register a POST route handler.
    func post(_ path: String, handler: @escaping RouteHandler) {
        routes.append((method: "POST", pathPattern: path, handler: handler))
    }

    /// Start listening on the specified port.
    func start(port: UInt16) throws {
        guard !isRunning else { return }

        serverSocket = socket(AF_INET6, SOCK_STREAM, 0)
        guard serverSocket >= 0 else {
            throw NSError(domain: "HTTPServer", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Failed to create socket"])
        }

        // Allow IPv4 connections on the IPv6 socket
        var noValue: Int32 = 0
        setsockopt(serverSocket, IPPROTO_IPV6, IPV6_V6ONLY, &noValue, socklen_t(MemoryLayout<Int32>.size))

        // Reuse address
        var reuseAddr: Int32 = 1
        setsockopt(serverSocket, SOL_SOCKET, SO_REUSEADDR, &reuseAddr, socklen_t(MemoryLayout<Int32>.size))

        // Bind
        var addr = sockaddr_in6()
        addr.sin6_family = sa_family_t(AF_INET6)
        addr.sin6_port = port.bigEndian
        addr.sin6_addr = in6addr_any

        let bindResult = withUnsafePointer(to: &addr) { addrPtr in
            addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPtr in
                bind(serverSocket, sockaddrPtr, socklen_t(MemoryLayout<sockaddr_in6>.size))
            }
        }

        guard bindResult == 0 else {
            close(serverSocket)
            serverSocket = -1
            throw NSError(domain: "HTTPServer", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "Failed to bind to port \(port): errno \(errno)"])
        }

        // Listen with a backlog of 16 connections
        guard listen(serverSocket, 16) == 0 else {
            close(serverSocket)
            serverSocket = -1
            throw NSError(domain: "HTTPServer", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "Failed to listen on port \(port)"])
        }

        self.port = port
        isRunning = true

        // Accept connections on background queue
        acceptQueue.async { [weak self] in
            self?.acceptLoop()
        }
    }

    /// Stop the server.
    func stop() {
        isRunning = false
        if serverSocket >= 0 {
            close(serverSocket)
            serverSocket = -1
        }
    }

    // MARK: - Private

    private func acceptLoop() {
        while isRunning {
            var clientAddr = sockaddr_in6()
            var addrLen = socklen_t(MemoryLayout<sockaddr_in6>.size)

            let clientSocket = withUnsafeMutablePointer(to: &clientAddr) { addrPtr in
                addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPtr in
                    accept(serverSocket, sockaddrPtr, &addrLen)
                }
            }

            guard clientSocket >= 0 else {
                if isRunning {
                    // Brief pause before retrying on transient errors
                    Thread.sleep(forTimeInterval: 0.01)
                }
                continue
            }

            // Set socket timeout (30 seconds for TTS generation)
            var timeout = timeval(tv_sec: 30, tv_usec: 0)
            setsockopt(clientSocket, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            setsockopt(clientSocket, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

            handlerQueue.async { [weak self] in
                self?.handleConnection(clientSocket)
            }
        }
    }

    private func handleConnection(_ clientSocket: Int32) {
        defer { close(clientSocket) }

        // Read the request (up to 1MB for POST bodies)
        guard let request = readRequest(from: clientSocket) else { return }

        // Handle CORS preflight
        if request.method == "OPTIONS" {
            let response = HTTPResponse(
                statusCode: 204,
                statusMessage: "No Content",
                headers: [
                    "Access-Control-Allow-Origin": "*",
                    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
                    "Access-Control-Allow-Headers": "Content-Type, Authorization",
                    "Access-Control-Max-Age": "86400",
                ]
            )
            sendResponse(response, to: clientSocket)
            return
        }

        // Find matching route
        let handler = findHandler(for: request)
        let semaphore = DispatchSemaphore(value: 0)
        var response: HTTPResponse = .error("Internal Server Error")

        Task {
            response = await handler(request)
            semaphore.signal()
        }

        semaphore.wait()
        sendResponse(response, to: clientSocket)
    }

    private func readRequest(from socket: Int32) -> HTTPRequest? {
        var buffer = [UInt8](repeating: 0, count: 8192)
        var requestData = Data()
        var headerEndIndex: Data.Index?

        // Read until we find the header terminator
        let headerTerminator = Data("\r\n\r\n".utf8)

        repeat {
            let bytesRead = recv(socket, &buffer, buffer.count, 0)
            guard bytesRead > 0 else { return nil }
            requestData.append(contentsOf: buffer[0..<bytesRead])

            if let range = requestData.range(of: headerTerminator) {
                headerEndIndex = range.upperBound
                break
            }
        } while requestData.count < 65536

        guard let headerEnd = headerEndIndex else { return nil }

        // Parse request line and headers
        let headerData = requestData[requestData.startIndex..<headerEnd]
        guard let headerString = String(data: headerData, encoding: .utf8) else { return nil }

        let lines = headerString.split(separator: "\r\n", omittingEmptySubsequences: false)
        guard let requestLine = lines.first else { return nil }

        let requestParts = requestLine.split(separator: " ", maxSplits: 2)
        guard requestParts.count >= 2 else { return nil }

        let method = String(requestParts[0])
        let rawPath = String(requestParts[1])
        let (path, queryParams) = HTTPRequest.parseQuery(rawPath)

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if line.isEmpty { break }
            let parts = line.split(separator: ":", maxSplits: 1)
            if parts.count == 2 {
                headers[String(parts[0]).trimmingCharacters(in: .whitespaces).lowercased()] =
                    String(parts[1]).trimmingCharacters(in: .whitespaces)
            }
        }

        // Read body if Content-Length is specified
        var body = Data()
        if let contentLengthStr = headers["content-length"],
           let contentLength = Int(contentLengthStr),
           contentLength > 0 {
            // We may have already read part of the body
            let bodyStart = headerEnd
            let alreadyRead = requestData.count - (bodyStart - requestData.startIndex)
            if alreadyRead > 0 {
                body.append(requestData[bodyStart...])
            }

            // Read remaining body bytes
            var remaining = contentLength - body.count
            var bodyBuffer = [UInt8](repeating: 0, count: min(remaining, 65536))
            while remaining > 0 {
                let toRead = min(remaining, bodyBuffer.count)
                let bytesRead = recv(socket, &bodyBuffer, toRead, 0)
                guard bytesRead > 0 else { break }
                body.append(contentsOf: bodyBuffer[0..<bytesRead])
                remaining -= bytesRead
            }
        }

        return HTTPRequest(
            method: method,
            path: path,
            queryParams: queryParams,
            headers: headers,
            body: body
        )
    }

    private func sendResponse(_ response: HTTPResponse, to socket: Int32) {
        let data = response.serialize()
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            guard let baseAddress = buffer.baseAddress else { return }
            var totalSent = 0
            while totalSent < data.count {
                let sent = send(socket, baseAddress.advanced(by: totalSent), data.count - totalSent, 0)
                if sent <= 0 { break }
                totalSent += sent
            }
        }
    }

    private func findHandler(for request: HTTPRequest) -> RouteHandler {
        for route in routes {
            if route.method == request.method && matchPath(pattern: route.pathPattern, path: request.path) {
                return route.handler
            }
        }
        // Default 404 handler
        return { _ in HTTPResponse.error("Not Found", statusCode: 404) }
    }

    /// Simple path matching supporting exact matches and trailing wildcards.
    private func matchPath(pattern: String, path: String) -> Bool {
        if pattern == path { return true }
        if pattern.hasSuffix("/*") {
            let prefix = String(pattern.dropLast(2))
            return path == prefix || path.hasPrefix(prefix + "/")
        }
        return false
    }
}
