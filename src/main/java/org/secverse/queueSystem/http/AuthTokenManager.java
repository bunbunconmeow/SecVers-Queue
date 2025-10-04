package org.secverse.queueSystem.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;


public final class AuthTokenManager {
    private final Path tokenFile;
    private volatile String token; // cached after load

    public AuthTokenManager(Path tokenFile) {
        this.tokenFile = Objects.requireNonNull(tokenFile, "tokenFile");
    }


    public synchronized String loadOrCreate() throws IOException {
        if (token != null) return token;

        if (Files.exists(tokenFile)) {
            token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (!token.isEmpty()) return token;
        }

        token = generateToken(48); // 384-bit random
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, token, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            // Try to restrict perms where supported
            Files.setPosixFilePermissions(tokenFile,
                    java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {

        }
        return token;
    }


    public boolean isAuthorized(String authorizationHeader) {
        if (authorizationHeader == null) return false;
        String expected = "Bearer " + token;
        return constantTimeEquals(authorizationHeader.trim(), expected);
    }

    private static String generateToken(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }


    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
