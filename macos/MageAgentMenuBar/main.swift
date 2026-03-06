import Cocoa

// MARK: - Application Entry Point
// Using NSApplicationMain pattern for proper object lifecycle management
// The AppDelegate is stored as a strong reference to prevent deallocation

// Strong reference to prevent ARC from deallocating the delegate
// This is CRITICAL - NSApplication.delegate is a WEAK reference
private var appDelegateReference: AppDelegate!

autoreleasepool {
    let app = NSApplication.shared
    appDelegateReference = AppDelegate()
    app.delegate = appDelegateReference
    app.run()
}
