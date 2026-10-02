package com.labconnect.core.security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

public final class TlsContextManager {
    private static final Logger log = LoggerFactory.getLogger(TlsContextManager.class);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final String localDeviceId;
    private final KeyPairGenerator keyPairGenerator;
    private final TrustStore trustStore;
    private SSLContext sslContext;
    private PrivateKey localPrivateKey;
    private java.security.cert.X509Certificate localCertificate;

    public TlsContextManager(String localDeviceId, KeyPairGenerator keyPairGenerator, TrustStore trustStore) throws Exception {
        this.localDeviceId = localDeviceId;
        this.keyPairGenerator = keyPairGenerator;
        this.trustStore = trustStore;
        initialize();
    }

    private void initialize() throws Exception {
        // Load or generate key pair
        loadOrGenerateIdentity();

        // For now, skip self-signed certificate generation
        // In production, use proper certificate generation with BouncyCastle
        // For now, we'll create a minimal TLS context without client cert auth
        initializeSslContext();
        
        log.info("TLS context initialized for device: {}", localDeviceId);
    }

    private void loadOrGenerateIdentity() throws Exception {
        // Generate new key pair
        var keyPair = keyPairGenerator.generate();
        
        // Parse private key
        byte[] privKeyBytes = org.bouncycastle.util.encoders.Base64.decode(keyPair.privateKeyBase64());
        PKCS8EncodedKeySpec privSpec = new PKCS8EncodedKeySpec(privKeyBytes);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        localPrivateKey = kf.generatePrivate(privSpec);

        // Parse public key
        byte[] pubKeyBytes = org.bouncycastle.util.encoders.Base64.decode(keyPair.publicKeyBase64());
        X509EncodedKeySpec pubSpec = new X509EncodedKeySpec(pubKeyBytes);
        PublicKey publicKey = kf.generatePublic(pubSpec);
    }

    private void initializeSslContext() throws Exception {
        // Initialize SSL context without client certificate authentication for now
        // In production, implement proper certificate generation with BouncyCastle
        sslContext = SSLContext.getInstance("TLSv1.3");
        
        // Use default trust managers for now
        // In production, implement proper trust manager with peer certificates
        sslContext.init(null, null, new SecureRandom());

        log.info("TLS context initialized for device: {}", localDeviceId);
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    public SSLEngine createSSLEngine(boolean useClientMode) {
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(useClientMode);
        // Disable client auth for now - enable in production with proper certs
        engine.setNeedClientAuth(false);
        engine.setWantClientAuth(false);
        return engine;
    }

    public java.security.cert.X509Certificate getLocalCertificate() {
        return localCertificate;
    }

    public String getLocalDeviceId() {
        return localDeviceId;
    }
}