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
}
