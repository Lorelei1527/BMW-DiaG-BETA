package com.tms.bmwediag.sgbd;

/**
 * ส่วนติดต่อสายสื่อสารสำหรับตัวรัน SGBD
 * (ดัดแปลงแนวคิดจาก EdInterfaceBase ของ EdiabasLib — GPLv3)
 */
public abstract class EdInterface {
    protected Sgbd ediabas;
    protected long[] commParameter;
    protected short[] commAnswerLen = {0, 0};
    protected long commRepeats = 0;
    protected boolean connected;

    void setEdiabas(Sgbd e) { ediabas = e; }

    public boolean isConnected() { return connected; }

    public void connect() throws SgbdException { connected = true; }

    public void disconnect() { connected = false; }

    public void setCommParameter(long[] par) throws SgbdException { commParameter = par; commAnswerLen = new short[]{0, 0}; }

    public void setCommAnswerLen(short[] len) { commAnswerLen = len; }

    public void setCommRepeats(long r) { commRepeats = r; }

    /** ส่ง telegram (ไม่รวม checksum) คืนคำตอบทั้งเฟรม (รวม checksum) หรือ null + setError */
    public abstract byte[] transmit(byte[] request) throws SgbdException;

    public byte[] keyBytes() { return null; }

    public byte[] state() { return new byte[]{0, 0, 0}; }

    public long batteryVoltage() { return 12000; }

    public long ignitionVoltage() { return 12000; }

    public String interfaceType() { return "OBD"; }

    public long interfaceVersion() { return 209; }

    // ---------- แคชคำตอบระหว่างอ่าน live data 1 รอบ ----------
    // หลาย job (เช่น STATUS_MOTORDREHZAHL, STATUS_UBATT ของ MS42/MS43) ส่งคำสั่งเดียวกัน (0B 03)
    // ในรอบเดียวกันส่งไปกล่องครั้งเดียวพอ ที่เหลือใช้คำตอบเดิม → เร็วขึ้นหลายเท่า
    private java.util.Map<String, byte[]> cache;

    public void beginCycle() { cache = new java.util.HashMap<>(); }

    public void endCycle() { cache = null; }

    protected byte[] cached(byte[] req) {
        if (cache == null || req == null || req.length == 0) return null;
        byte[] r = cache.get(key(req));
        return r == null ? null : r.clone();
    }

    protected void remember(byte[] req, byte[] resp) {
        if (cache != null && req != null && req.length > 0 && resp != null) cache.put(key(req), resp.clone());
    }

    private static String key(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append((char) ('A' + ((x >> 4) & 15))).append((char) ('A' + (x & 15)));
        return sb.toString();
    }
}
