package com.tms.bmwediag.diag;

import java.util.ArrayList;
import java.util.List;

public class EcuDef {
    public enum Protocol { DS2, KWP, OBD, AUTO }

    public String id, name, notes = "";
    public Protocol protocol;
    public int address = 0x12;
    public int baud = 9600;
    public int parity = 2; // 0 none,1 odd,2 even
    public List<byte[]> ident = new ArrayList<>();
    public byte[] faultRead, faultClear;
    /** คำสั่งอ่าน fault หลายชุด ("XX" = ใส่ลำดับ 1..จำนวนโค้ด) */
    public List<String> faultReads = new ArrayList<>();
    public String sgbd = "";
    /** กล่องอยู่บน D-CAN (E9x) — ใช้ ELM327 */
    public boolean can;
    /** ช่องทางที่ใช้ได้: USB, ELM, ANY */
    public String link = "ANY";
    /** DS2: จำนวนไบต์ต่อ 1 โค้ด (สำหรับแยกแสดงผล) / KWP: ใช้รูปแบบ 0x58 */
    public int ds2FaultEntrySize = 0;
    public int ds2FaultHeader = 1;
    public List<LiveRequest> live = new ArrayList<>();
    /** คำอธิบายโค้ด fault ของโมดูลนี้ (รหัส -> ข้อความไทย) */
    public java.util.Map<Integer, String> faultText = new java.util.HashMap<>();

    @Override
    public String toString() { return name; }
}
