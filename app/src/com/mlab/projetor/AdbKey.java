package com.mlab.projetor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * Chave RSA 2048 do instalador, no formato que o adbd espera. Gerada uma vez e guardada no
 * armazenamento privado do app: com "Sempre permitir" no projetor, ele nao pergunta de novo.
 */
final class AdbKey {
    /** DigestInfo do SHA-1: o token de 20 bytes do adbd e assinado como se fosse um hash SHA-1. */
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final int WORDS = 2048 / 32;

    private final RSAPrivateCrtKey priv;
    private final RSAPublicKey pub;

    private AdbKey(RSAPrivateCrtKey priv, RSAPublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    static AdbKey loadOrCreate(File file) throws IOException, GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (file.isFile()) {
            byte[] der = readAll(file);
            RSAPrivateCrtKey k = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPublicKey p = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(k.getModulus(), k.getPublicExponent()));
            return new AdbKey(k, p);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(kp.getPrivate().getEncoded());
        }
        if (!tmp.renameTo(file)) throw new IOException("nao foi possivel salvar a chave");
        return new AdbKey((RSAPrivateCrtKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    /** Assina o token do AUTH (PKCS#1 v1.5 com o prefixo do SHA-1, como o adb do computador). */
    byte[] sign(byte[] token) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, priv);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /** Chave publica no formato do adb (estrutura RSAPublicKey do mincrypt, em base64) + nome + \0. */
    byte[] publicKeyPayload(String name) {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(WORDS * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(WORDS);
        b.putInt(n0inv.intValue());
        putWords(b, n);
        putWords(b, rr);
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.getEncoder().encodeToString(b.array()) + " " + name + "\u0000";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < WORDS; i++) {
            b.putInt(v.shiftRight(i * 32).and(mask).intValue());
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
