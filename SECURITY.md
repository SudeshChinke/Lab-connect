# Security status

This document describes the code that currently runs, not the intended future design.

## Current behavior

- Desktop peer connections use plain TCP. Chat messages, pairing frames, and file contents are not encrypted in transit.
- Any host that can reach the TCP port can open a connection. Pairing does not block unpaired peers from connecting or sending data.
- The desktop creates a persistent Ed25519 identity and derives its device ID from the public key. The key is exchanged during discovery/HELLO, but HELLO itself is not authenticated.
- Desktop pairing requests, accept/reject responses, a fingerprint prompt, and persistence of accepted public keys are implemented. Users should compare fingerprints through a separate trusted channel. This reduces accidental trust but does not protect a plaintext connection from an active attacker.
- File transfer checks a SHA-256 hash to detect accidental or malicious content changes against the hash in the transfer request. Without authenticated transport, an active network attacker can alter both the data and advertised hash.
- `TlsContextManager` contains TLS certificate/context code, but it is not used by the socket transport. TLS configuration settings do not turn encryption on.
- Android currently implements discovery, direct text messaging, and file transfers, but it has no TLS transport or pairing enforcement. Its private Ed25519 key is stored as Base64 in app-private SharedPreferences without encryption.

## Local data

- The desktop Ed25519 identity is stored in `keystore.dat` in plaintext Base64. POSIX permissions are restricted where supported, but the file is not password-encrypted.
- Trusted peer public keys are stored in `truststore.properties` in plaintext. These are public keys, not private credentials.
- Do not share either file publicly. Back up `keystore.dat` securely if you need to preserve the device identity.

## Safe use

Use LabConnect only for testing on a network you trust. Do not use it for private conversations, sensitive files, or security-critical workflows until TLS is integrated into every TCP connection, peer identity is verified against the trust store, unpaired traffic is rejected where configured, and those behaviors have end-to-end tests.

## Work required before secure use

1. Integrate TLS 1.3 mutual authentication into the non-blocking TCP transport.
2. Bind the TLS certificate identity to the device ID exchanged in HELLO.
3. Enforce pairing policy before application frames are accepted or sent.
4. Add tests proving encrypted transfer, rejection of untrusted peers, and rejection of mismatched identities.
5. Move Android identity storage to Android Keystore-backed encryption and bring its transport/security behavior to parity.
