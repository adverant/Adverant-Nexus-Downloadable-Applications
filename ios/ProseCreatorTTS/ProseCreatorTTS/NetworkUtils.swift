// NetworkUtils.swift
// Utilities for discovering the device's local network IP address.
// Used to display the TTS server URL that the ProseCreator web app connects to.

import Foundation
import Network

/// Network utility functions for the TTS server.
enum NetworkUtils {
    /// Get the device's WiFi IP address on the local network.
    /// Returns nil if not connected to WiFi or unable to determine address.
    static func getWiFiIPAddress() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?

        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else {
            return nil
        }

        defer { freeifaddrs(ifaddr) }

        var ptr = firstAddr
        while true {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family

            // Look for IPv4 addresses on WiFi interface (en0)
            if addrFamily == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                if name == "en0" {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    let result = getnameinfo(
                        interface.ifa_addr,
                        socklen_t(interface.ifa_addr.pointee.sa_len),
                        &hostname,
                        socklen_t(hostname.count),
                        nil, 0,
                        NI_NUMERICHOST
                    )
                    if result == 0 {
                        address = String(cString: hostname)
                    }
                }
            }

            guard let next = interface.ifa_next else { break }
            ptr = next
        }

        return address
    }

    /// Get all available IP addresses across all network interfaces.
    /// Useful for debugging connectivity issues.
    static func getAllIPAddresses() -> [(interface: String, address: String)] {
        var addresses: [(interface: String, address: String)] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?

        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else {
            return addresses
        }

        defer { freeifaddrs(ifaddr) }

        var ptr = firstAddr
        while true {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family

            if addrFamily == UInt8(AF_INET) || addrFamily == UInt8(AF_INET6) {
                let name = String(cString: interface.ifa_name)
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let result = getnameinfo(
                    interface.ifa_addr,
                    socklen_t(interface.ifa_addr.pointee.sa_len),
                    &hostname,
                    socklen_t(hostname.count),
                    nil, 0,
                    NI_NUMERICHOST
                )
                if result == 0 {
                    let addr = String(cString: hostname)
                    // Skip link-local and loopback
                    if !addr.hasPrefix("fe80") && addr != "127.0.0.1" && addr != "::1" {
                        addresses.append((interface: name, address: addr))
                    }
                }
            }

            guard let next = interface.ifa_next else { break }
            ptr = next
        }

        return addresses
    }

    /// Format the server URL for display.
    static func serverURL(port: UInt16) -> String {
        if let ip = getWiFiIPAddress() {
            return "http://\(ip):\(port)"
        }
        return "http://localhost:\(port)"
    }

    /// Check if a specific port is available for binding.
    static func isPortAvailable(_ port: UInt16) -> Bool {
        let socketFD = socket(AF_INET, SOCK_STREAM, 0)
        guard socketFD >= 0 else { return false }
        defer { close(socketFD) }

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY

        var reuseAddr: Int32 = 1
        setsockopt(socketFD, SOL_SOCKET, SO_REUSEADDR, &reuseAddr, socklen_t(MemoryLayout<Int32>.size))

        let bindResult = withUnsafePointer(to: &addr) { addrPtr in
            addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPtr in
                bind(socketFD, sockaddrPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        return bindResult == 0
    }
}
