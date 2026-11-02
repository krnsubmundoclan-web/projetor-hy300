package com.mlab.projetor;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

final class Net {
    private Net() {}

    /** IPv4 da rede local (prefere wlan0). */
    static String ip() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? fallback : "sem rede";
    }

    /** Endereco da rede de casa: privado, link-local, loopback, IPv6 ULA ou CGNAT/VPN (100.64/10). */
    static boolean isLocal(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 16) {
            // IPv4 dentro de IPv6 (::ffff:a.b.c.d): vale a regra do IPv4.
            boolean mapped = (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped) {
                try {
                    return isLocal(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (Exception e) {
                    return false;
                }
            }
            return (b[0] & 0xFE) == 0xFC;
        }
        return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    /** MAC de um aparelho da rede pela tabela ARP; null se o sistema nao mostrar. */
    static String mac(String ip) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 4 && p[0].equals(ip) && !p[3].equals("00:00:00:00:00:00")) return p[3];
            }
        } catch (java.io.IOException | RuntimeException ignored) {
        }
        return null;
    }
}
