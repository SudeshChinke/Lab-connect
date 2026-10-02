package com.labconnect.core.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ChecksumEngine {
    private static final Logger log = LoggerFactory.getLogger(ChecksumEngine.class);
    private static final int BUFFER_SIZE = 8192;

    public static String computeSHA256(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            return computeSHA256(is);
        }
    }

    public static String computeSHA256(InputStream is) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    public static String computeSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data);
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static void verifyAndCompute(Path file, String expectedHash, OutputStream output) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
                if (output != null) {
                    output.write(buffer, 0, bytesRead);
                }
            }
            String computed = bytesToHex(digest.digest());
            if (!computed.equalsIgnoreCase(expectedHash)) {
                throw new ChecksumMismatchException(
                        "Checksum mismatch: expected " + expectedHash + ", got " + computed);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    public static class StreamingVerifier {
        private final MessageDigest digest;
        private final String expectedHash;
        private long bytesProcessed = 0;

        public StreamingVerifier(String expectedHash) {
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
                this.expectedHash = expectedHash.toLowerCase();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }

        public void update(byte[] data) {
            digest.update(data);
        }

        public void update(byte[] data, int offset, int length) {
            digest.update(data, offset, length);
        }

        public void verify() throws ChecksumMismatchException {
            String computed = bytesToHex(digest.digest()).toLowerCase();
            if (!computed.equals(expectedHash)) {
                throw new ChecksumMismatchException(
                        "Checksum mismatch: expected " + expectedHash + ", got " + computed);
            }
        }

        public String getComputedHash() {
            return bytesToHex(digest.digest()).toLowerCase();
        }
    }

    public static class ChecksumMismatchException extends IOException {
        public ChecksumMismatchException(String message) {
            super(message);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}