import Foundation

let path = "/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md"
do {
    let str = try String(contentsOfFile: path, encoding: .utf8)
    print("Swift read success:", str.prefix(100))
} catch {
    print("Swift read error:", error)
}
