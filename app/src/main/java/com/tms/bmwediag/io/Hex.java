package com.tms.bmwediag.io;

public final class Hex {
    private Hex() {}

    public static String of(byte[] b) { return b == null ? "" : of(b, 0, b.length); }

    public static String of(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = off; i < off + len; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    /** "12 04 00" / "120400" / "0x12,0x04" -> bytes */
    public static byte[] parse(String s) {
        if (s == null) return new byte[0];
        String c = s.replaceAll("(?i)0x", "").replaceAll("[^0-9A-Fa-f]", "");
        if (c.length() % 2 != 0) throw new IllegalArgumentException("จำนวนตัวอักษร hex ต้องเป็นคู่: " + s);
        byte[] out = new byte[c.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(c.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static String ascii(byte[] b, int off) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < b.length; i++) {
            int c = b[i] & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        return sb.toString();
    }
}
