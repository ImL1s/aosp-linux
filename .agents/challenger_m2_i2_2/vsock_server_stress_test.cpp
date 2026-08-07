#include "system/linux_bridge/vsock_server.h"
#include "system/linux_bridge/hmac_auth.h"
#include <iostream>
#include <cassert>
#include <vector>
#include <cstring>

using namespace android::system::linux_bridge;

static AuthHandshakePayload makePayload(const std::vector<uint8_t>& secret, const std::vector<uint8_t>& token) {
    AuthHandshakePayload p;
    std::memcpy(p.token, token.data(), 32);
    std::vector<uint8_t> sig = HmacAuth::computeHmacSha256(secret, token);
    std::memcpy(p.signature, sig.data(), 32);
    return p;
}

int main() {
    std::cout << "=== Running Challenger 2 VsockServer Direct C++ Stress Test ===" << std::endl;

    VsockServer server;
    server.start();

    // 1. Verify initial unauthenticated state
    assert(!server.isAuthenticated());
    std::cout << "[PASS] Initial state is unauthenticated." << std::endl;

    // 2. Test unauthenticated bind to Port 5001 (PTY) -> MUST FAIL (return false)
    bool bind5001_unauth = server.bindPort(VSOCK_PORT_PTY);
    assert(!bind5001_unauth);
    std::cout << "[PASS] Unauthenticated bind to Port 5001 (PTY) returned false as required." << std::endl;

    // 3. Test unauthenticated bind to Port 5002 (Wayland) -> MUST FAIL (return false)
    bool bind5002_unauth = server.bindPort(VSOCK_PORT_WAYLAND);
    assert(!bind5002_unauth);
    std::cout << "[PASS] Unauthenticated bind to Port 5002 (Wayland) returned false as required." << std::endl;

    // 4. Test bind to unreserved port 9999 -> MUST FAIL
    bool bind9999 = server.bindPort(9999);
    assert(!bind9999);
    std::cout << "[PASS] Bind to unreserved port 9999 returned false." << std::endl;

    // 5. Test bind to Port 5000 (Control) -> MUST SUCCEED
    bool bind5000 = server.bindPort(VSOCK_PORT_CONTROL);
    assert(bind5000);
    std::cout << "[PASS] Bind to Port 5000 (Control) succeeded." << std::endl;

    // 6. Test unauthorized CID handshake rejection (CID 99 != ALLOWED_GUEST_CID 3)
    std::vector<uint8_t> token(32, 0xAB);
    std::vector<uint8_t> secret(32, 0xCD);
    server.setAuthToken(token, secret);

    AuthHandshakePayload payload = makePayload(secret, token);
    bool badCidHandshake = server.processHandshake(99, payload);
    assert(!badCidHandshake);
    assert(!server.isAuthenticated());
    std::cout << "[PASS] Handshake from unauthorized CID 99 rejected." << std::endl;

    // 7. Test valid handshake from ALLOWED_GUEST_CID 3
    bool validHandshake = server.processHandshake(3, payload);
    assert(validHandshake);
    assert(server.isAuthenticated());
    std::cout << "[PASS] Valid handshake from CID 3 succeeded and session authenticated." << std::endl;

    // 8. Test authenticated bind to Port 5001 and 5002 -> MUST SUCCEED
    bool bind5001_auth = server.bindPort(VSOCK_PORT_PTY);
    assert(bind5001_auth);
    std::cout << "[PASS] Authenticated bind to Port 5001 (PTY) succeeded." << std::endl;

    bool bind5002_auth = server.bindPort(VSOCK_PORT_WAYLAND);
    assert(bind5002_auth);
    std::cout << "[PASS] Authenticated bind to Port 5002 (Wayland) succeeded." << std::endl;

    // 9. Test duplicate bind attempt (collision rejection)
    bool bind5001_dup = server.bindPort(VSOCK_PORT_PTY);
    assert(!bind5001_dup);
    std::cout << "[PASS] Duplicate bind attempt to Port 5001 returned false." << std::endl;

    // 10. Test single-use token replay rejection
    server.resetSession();
    assert(!server.isAuthenticated());
    std::vector<uint8_t> token2(32, 0xEE);
    server.setAuthToken(token2, secret);

    AuthHandshakePayload payload2 = makePayload(secret, token2);
    bool attempt1 = server.processHandshake(3, payload2);
    assert(attempt1);
    
    // Attempt replay of payload2
    bool attempt2 = server.processHandshake(3, payload2);
    assert(!attempt2);
    std::cout << "[PASS] Replayed single-use token rejected on second attempt." << std::endl;

    server.stop();
    std::cout << "=== ALL VSOCK SERVER STRESS TESTS PASSED ===" << std::endl;
    return 0;
}
