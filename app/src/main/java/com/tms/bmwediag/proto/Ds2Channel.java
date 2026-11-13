package com.tms.bmwediag.proto;

import com.tms.bmwediag.io.Hex;

import java.io.IOException;

/**
 * BMW DS2 (E36/E38/E39/E46/E53/E83/E85 ผ่าน K-line 9600 8E1)
 * เฟรม: [address][length รวมทั้งหมด][data...][XOR checksum]
 * ตอบกลับ: [address][length][status A0=OK][data...][XOR]
 */
public class Ds2Channel implements EcuChannel {
    public static final int ACK = 0xA0, BUSY = 0xA1, REJECT = 0xA2, ERROR = 0xFF;

    private final KLine k;
    private final int address;
    public int timeoutMs = 1500;
    private boolean dirty = true;

    public Ds2Channel(KLine k, int address) {
        this.k = k;
        this.address = address;
    }

    public static byte[] frame(int address, byte[] data) {
        byte[] f = new byte[data.length + 3];
        f[0] = (byte) address;
        f[1] = (byte) f.length;
        System.arraycopy(data, 0, f, 2, data.length);
        f[f.length - 1] = (byte) KLine.xor(f, 0, f.length - 1);
        return f;
    }

    /** คืนข้อมูลตั้งแต่ status byte (A0 ...) ไม่รวม checksum */
    public static byte[] unframe(byte[] f) throws IOException {
        if (f.length < 4) throw new IOException("DS2 เฟรมสั้นเกินไป");
        int cs = KLine.xor(f, 0, f.length - 1);
        if (cs != (f[f.length - 1] & 0xFF)) throw new IOException("DS2 checksum ผิด: " + Hex.of(f));
        byte[] d = new byte[f.length - 3];
        System.arraycopy(f, 2, d, 0, d.length);
        return d;
    }

    @Override
    public byte[] request(byte[] payload) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (dirty) { k.purge(); dirty = false; } else k.purgeLight();
            k.send(frame(address, payload));
            byte[] hdr = k.readExact(2, timeoutMs);
            if (hdr == null) dirty = true;
            if (hdr == null) throw new IOException("DS2: ไม่มีการตอบกลับจาก 0x" + String.format("%02X", address));
            int len = hdr[1] & 0xFF;
            if (len < 4) throw new IOException("DS2 ความยาวผิด " + Hex.of(hdr));
            byte[] rest = k.readExact(len - 2, 500);
            if (rest == null) { dirty = true; throw new IOException("DS2: ข้อมูลตอบกลับไม่ครบ"); }
            byte[] f = new byte[len];
            System.arraycopy(hdr, 0, f, 0, 2);
            System.arraycopy(rest, 0, f, 2, rest.length);
            k.trace("RX " + Hex.of(f));
            byte[] d;
            try { d = unframe(f); } catch (IOException e) { dirty = true; throw e; }
            int st = d[0] & 0xFF;
            if (st == BUSY) { KLine.sleep(100); continue; }
            if (st == REJECT) throw new IOException("DS2: กล่องปฏิเสธคำสั่ง (A2)");
            if (st == ERROR) throw new IOException("DS2: คำสั่งไม่รองรับ (FF)");
            return d;
        }
        throw new IOException("DS2: กล่อง busy");
    }

    @Override
    public String describe() { return String.format("DS2 @0x%02X 9600 8E1", address); }

    @Override
    public void close() {}
}
