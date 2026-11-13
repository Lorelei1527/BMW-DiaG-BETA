package com.tms.bmwediag.proto;

import java.io.IOException;
import java.util.List;

/** ช่องสื่อสารกับกล่อง 1 ตัว: ส่ง payload (service + data) คืน payload ตอบกลับ */
public interface EcuChannel {
    byte[] request(byte[] payload) throws IOException;

    /** บางคำสั่ง (เช่น OBD โหมด 03) ตอบหลายเฟรม */
    default List<byte[]> requestMulti(byte[] payload) throws IOException {
        return java.util.Collections.singletonList(request(payload));
    }

    /** คำขอที่รู้ความยาวคำตอบ (เช่น OBD PID) — ตอบกลับทันทีไม่ต้องรอสายเงียบ */
    default byte[] requestExpect(byte[] payload, int responseLen) throws IOException {
        return request(payload);
    }

    /** ล็อกให้รับเฉพาะคำตอบจาก ECU address นี้ (-1 = รับทุกตัว) — กันค่าจากกล่องเกียร์ปนกับ DME */
    default void setSourceFilter(int src) {}

    /** address ของผู้ตอบแต่ละเฟรมจาก requestMulti ล่าสุด (ถ้ารู้) */
    default List<Integer> lastSources() { return java.util.Collections.emptyList(); }

    String describe();

    void close();
}
