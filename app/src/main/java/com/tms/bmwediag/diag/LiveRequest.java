package com.tms.bmwediag.diag;

import java.util.ArrayList;
import java.util.List;

public class LiveRequest {
    public byte[] request;
    /** จำนวนไบต์หัวคำตอบที่ข้ามก่อนนับ offset (DS2: 1 = status A0, KWP 0x21: 2 = 61 xx) */
    public int skip = 1;
    public List<LiveParam> params = new ArrayList<>();
    /** ถ้าไม่มี params จะแสดงทุกไบต์ดิบ (ใช้หา offset เอง) */
    public boolean showRaw;
}
