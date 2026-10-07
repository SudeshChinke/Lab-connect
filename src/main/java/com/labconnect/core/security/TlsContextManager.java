package com.labconnect.core.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.Date;

public final class TlsContextManager {
    private static final Logger log = LoggerFactory.getLogger(TlsContextManager.class);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final String localDeviceId;
    private final KeyPairGenerator.KeyPair identity;
    private final TrustStore trustStore;
    private SSLContext sslContext;
    private PrivateKey localPrivateKey;
    private PublicKey localPublicKey;
    private java.security.cert.X509Certificate localCertificate;

    public TlsContextManager(KeyPairGenerator.KeyPair identity, TrustStore trustStore) throws Exception {
        this.identity = identity;
        this.trustStore = trustStore;
        this.localDeviceId = KeyPairGenerator.deriveDeviceId(identity.publicKeyBase64());
        initialize();
    }

    private void initialize() throws Exception {
        loadIdentityFromKeypair();
        generateSelfSignedCertificate();
        initializeSslContext();
        
        log.info("TLS context initialized for device: {}", localDeviceId);
    }

    private void loadIdentityFromKeypair() throws Exception {
        // Parse private key from identity
        byte[] privKeyBytes = org.bouncycastle.util.encoders.Base64.decode(identity.privateKeyBase64());
        PKCS8EncodedKeySpec privSpec = new PKCS8EncodedKeySpec(privKeyBytes);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        localPrivateKey = kf.generatePrivate(privSpec);

        // Parse public key from identity
        byte[] pubKeyBytes = org.bouncycastle.util.encoders.Base64.decode(identity.publicKeyBase64());
        X509EncodedKeySpec pubSpec = new X509EncodedKeySpec(pubKeyBytes);
        localPublicKey = kf.generatePublic(pubSpec);
    }

    private void generateSelfSignedCertificate() throws Exception {
        // Generate a self-signed X.509 certificate using the Ed25519 key pair
        // Valid for 10 years
        long now = System.currentTimeMillis();
        org.bouncycastle.asn1.x509.Time notBefore = new org.bouncycastle.asn1.x509.Time(new Date(now));
        org.bouncycastle.asn1.x509.Time notAfter = new org.bouncycastle.asn1.x509.Time(
                new Date(now + 10L * 365 * 24 * 60 * 60 * 1000));

        // Subject and issuer are the same for self-signed
        X500Name subject = new X500Name("CN=" + localDeviceId);
        
        // Serial number
        BigInteger serial = BigInteger.valueOf(System.nanoTime());

        // Convert PublicKey to SubjectPublicKeyInfo for BouncyCastle
        org.bouncycastle.asn1.x509.SubjectPublicKeyInfo spki = 
                org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(localPublicKey.getEncoded());

        // Build certificate
        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, spki);

        // Add basic constraints (CA=false for end entity)
        certBuilder.addExtension(
                org.bouncycastle.asn1.x509.Extension.basicConstraints, 
                true, 
                new BasicConstraints(false)
        );

        // Add key usage
        certBuilder.addExtension(
                org.bouncycastle.asn1.x509.Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement)
        );

        // Add extended key usage for client/server auth
        certBuilder.addExtension(
                org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(new KeyPurposeId[] {
                        KeyPurposeId.id_kp_clientAuth,
                        KeyPurposeId.id_kp_serverAuth
                })
        );

        // Subject Alternative Name with deviceId
        GeneralName subjectAltName = new GeneralName(GeneralName.dNSName, localDeviceId);
        GeneralNames subjectAltNames = new GeneralNames(subjectAltName);
        certBuilder.addExtension(
                org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
                false,
                subjectAltNames
        );

        // Sign the certificate
        ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(localPrivateKey);
        org.bouncycastle.cert.X509CertificateHolder certHolder = certBuilder.build(signer);
        localCertificate = new JcaX509CertificateConverter().getCertificate(certHolder);

        // Verify the certificate
        localCertificate.verify(localPublicKey);
        
        log.debug("Generated self-signed certificate for {}", localDeviceId);
    }

    private void initializeSslContext() throws Exception {
        // Create KeyManager using our certificate and private key
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("device-identity", localPrivateKey, new char[0], 
                new java.security.cert.Certificate[]{localCertificate});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        // Create TrustManager that uses our TrustStore for peer verification
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        
        // Create a trust store with peer certificates
        KeyStore trustKeyStore = KeyStore.getInstance("PKCS12");
        trustKeyStore.load(null, null);
        
        // Add trusted peer certificates
        for (Map.Entry<String, TrustStore.TrustedPeer> entry : trustStore.getTrustedPeers().entrySet()) {
            // We'll add them dynamically during handshake via custom TrustManager
        }
        tmf.init(trustKeyStore);

        // Create custom trust manager that checks TrustStore
        X509TrustManager customTrustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) 
                    throws CertificateException {
                verifyPeerChain(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) 
                    throws CertificateException {
                verifyPeerChain(chain);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), new TrustManager[]{customTrustManager}, new SecureRandom());

        log.info("TLS 1.3 context initialized with mTLS for device: {}", localDeviceId);
    }

    private void verifyPeerChain(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Empty certificate chain");
        }

        X509Certificate peerCert = chain[0];
        
        // Extract deviceId from certificate subject CN
        String subjectCN = peerCert.getSubjectX500Principal().getName();
        String peerDeviceId = extractDeviceIdFromCN(subjectCN);
        
        if (peerDeviceId == null) {
            throw new CertificateException("Certificate missing deviceId in CN");
        }

        // Check if peer is in trust store
        Optional<TrustStore.TrustedPeer> trusted = trustStore.getTrustedPeer(peerDeviceId);
        if (trusted.isEmpty()) {
            throw new CertificateException("Peer not trusted: " + peerDeviceId);
        }

        // Verify certificate public key matches trusted public key
        String trustedPubKey = trusted.get().publicKey();
        byte[] trustedKeyBytes = org.bouncycastle.util.encoders.Base64.decode(trustedPubKey);
        
        if (!Arrays.equals(peerCert.getPublicKey().getEncoded(), trustedKeyBytes)) {
            throw new CertificateException("Peer certificate public key mismatch for " + peerDeviceId);
        }

        // Verify certificate is valid (not expired, etc.)
        peerCert.checkValidity();
        
        log.debug("Peer certificate verified: {}", peerDeviceId);
    }

    private String extractDeviceIdFromCN(String subjectCN) {
        // CN=DEVICE-XXXXXXXXXXXXXXXX
        if (subjectCN.startsWith("CN=")) {
            return subjectCN.substring(3);
        }
        return null;
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    public SSLEngine createSSLEngine(boolean useClientMode) {
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(useClientMode);
        engine.setNeedClientAuth(true);  // mTLS: require client certificate
        engine.setWantClientAuth(true);
        return engine;
    }

    public java.security.cert.X509Certificate getLocalCertificate() {
        return localCertificate;
    }

    public String getLocalDeviceId() {
        return localDeviceId;
    }

    public KeyPairGenerator.KeyPair getIdentity() {
        return identity;
    }
}