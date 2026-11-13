package com.tms.bmwediag.sgbd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** แปลชื่อค่า live data ของ SGBD (STAT_xxx) เป็นภาษาไทย */
public final class ThaiLabels {
    private ThaiLabels() {}

    private static final Map<String, String> EXACT = new HashMap<>();
    private static final Map<String, String> TOK = new HashMap<>();

    private static void e(String k, String v) { EXACT.put(k, v); }
    private static void t(String k, String v) { TOK.put(k, v); }

    static {
        // ---------- ค่าหลัก (ชื่อย่อ Bosch / Siemens) ----------
        e("NMOT", "รอบเครื่อง"); e("MOTORDREHZAHL", "รอบเครื่อง"); e("DREHZAHL", "รอบเครื่อง");
        e("NSOL", "รอบเดินเบาเป้าหมาย"); e("VFZG", "ความเร็วรถ"); e("GESCHWINDIGKEIT", "ความเร็วรถ");
        e("TMOT", "อุณหภูมิน้ำหล่อเย็น"); e("MOTORTEMPERATUR", "อุณหภูมิน้ำหล่อเย็น"); e("MOTORTEMP", "อุณหภูมิน้ำหล่อเย็น");
        e("TANS", "อุณหภูมิอากาศเข้า"); e("AN_LUFTTEMPERATUR", "อุณหภูมิอากาศเข้า"); e("AN_LUFTTEMP", "อุณหภูมิอากาศเข้า");
        e("TKA", "อุณหภูมิน้ำออกหม้อน้ำ"); e("KUEHLW_AUSL_TEMPERATUR", "อุณหภูมิน้ำออกหม้อน้ำ"); e("KUEHLW_AUSL_TEMP", "อุณหภูมิน้ำออกหม้อน้ำ");
        e("OEL_TEMPERATUR", "อุณหภูมิน้ำมันเครื่อง"); e("OEL_TEMP", "อุณหภูมิน้ำมันเครื่อง"); e("TOEL", "อุณหภูมิน้ำมันเครื่อง");
        e("MSHFM", "อากาศเข้า (MAF)"); e("LMM_MASSE", "อากาศเข้า (MAF)"); e("LMM", "อากาศเข้า (MAF)"); e("ML", "อากาศเข้า (MAF)");
        e("LMM_SPANNUNG", "แรงดันไฟ MAF"); e("MIIST", "แรงบิดจริง"); e("MISOL", "แรงบิดเป้าหมาย");
        e("UB", "แรงดันแบตเตอรี่"); e("UBATT", "แรงดันแบตเตอรี่"); e("KL15", "แรงดันไฟ KL15");
        e("UPWG", "แรงดันคันเร่ง"); e("PWG_WINKEL", "ตำแหน่งคันเร่ง"); e("PWG", "ตำแหน่งคันเร่ง"); e("GASPEDAL", "คันเร่ง (ตัวที่ 1)"); e("GASPEDAL_2", "คันเร่ง (ตัวที่ 2)");
        e("WDKBA", "มุมลิ้นปีกผีเสื้อ"); e("DKP_WINKEL", "มุมลิ้นปีกผีเสื้อ"); e("DROSSELKLAPPE", "มุมลิ้นปีกผีเสื้อ"); e("WDK", "มุมลิ้นปีกผีเสื้อ");
        e("ZWOUT", "องศาจุดระเบิด"); e("ZUENDWINKEL", "องศาจุดระเบิด"); e("ZW", "องศาจุดระเบิด");
        e("TE", "เวลาฉีดน้ำมัน"); e("TE_TASTVERHAELTNIS", "Duty หัวฉีด"); e("EINSPRITZZEIT", "เวลาฉีดน้ำมัน"); e("ZEIT_EINSPRITZ", "เวลาฉีดน้ำมัน"); e("TI", "เวลาฉีดน้ำมัน");
        e("FR", "Lambda correction (STFT) B1"); e("FR2", "Lambda correction (STFT) B2");
        e("INT", "Lambda correction (STFT) B1"); e("INT_2", "Lambda correction (STFT) B2");
        e("LAMBDA_INTEGRATOR_1", "Lambda correction (STFT) B1"); e("LAMBDA_INTEGRATOR_2", "Lambda correction (STFT) B2");
        e("ADD", "ค่าเรียนรู้ส่วนผสม (เพิ่ม) B1"); e("ADD_2", "ค่าเรียนรู้ส่วนผสม (เพิ่ม) B2");
        e("LAMBDA_ADD_1", "ค่าเรียนรู้ส่วนผสม (เพิ่ม) B1"); e("LAMBDA_ADD_2", "ค่าเรียนรู้ส่วนผสม (เพิ่ม) B2");
        e("MUL", "ค่าเรียนรู้ส่วนผสม (คูณ) B1"); e("MUL_2", "ค่าเรียนรู้ส่วนผสม (คูณ) B2");
        e("LAMBDA_MUL_1", "ค่าเรียนรู้ส่วนผสม (คูณ) B1"); e("LAMBDA_MUL_2", "ค่าเรียนรู้ส่วนผสม (คูณ) B2");
        e("L_SONDE", "O2 หน้าแคต B1"); e("L_SONDE_2", "O2 หน้าแคต B2"); e("L_SONDE_H", "O2 หลังแคต B1"); e("L_SONDE_2_H", "O2 หลังแคต B2");
        e("LS_VKAT_SIGNAL_1", "O2 หน้าแคต B1"); e("LS_VKAT_SIGNAL_2", "O2 หน้าแคต B2");
        e("LS_NKAT_SIGNAL_1", "O2 หลังแคต B1"); e("LS_NKAT_SIGNAL_2", "O2 หลังแคต B2");
        e("UULSUV", "แรงดัน O2 หน้าแคต B1"); e("UULSUV2", "แรงดัน O2 หน้าแคต B2");
        e("WNWKWE", "มุมแคมไอดี (VANOS)"); e("WNWKWA", "มุมแคมไอเสีย (VANOS)");
        e("VANOS_NW_LAGE_EINLASS", "มุมแคมไอดี (VANOS)"); e("VANOS_NW_LAGE_AUSLASS", "มุมแคมไอเสีย (VANOS)");
        e("VANOS_NW_LAGE_EINLASS_IST", "มุมแคมไอดี จริง"); e("VANOS_NW_LAGE_EINLASS_SOLL", "มุมแคมไอดี เป้าหมาย");
        e("VANOS_NW_LAGE_AUSLASS_IST", "มุมแคมไอเสีย จริง"); e("VANOS_NW_LAGE_AUSLASS_SOLL", "มุมแคมไอเสีย เป้าหมาย");
        e("LAST", "โหลดเครื่อง"); e("RL", "โหลดเครื่อง"); e("LUFTDRUCK", "ความกดอากาศ"); e("PU", "ความกดอากาศ");
        e("LL_INTEGRATOR", "ค่าปรับรอบเดินเบา"); e("LL_STELLER_TV", "Duty วาล์วเดินเบา (ICV)"); e("LL_LUFTBEDARF", "อากาศที่ต้องการตอนเดินเบา");
        e("KLOPF_ADC1", "แรงดันเซ็นเซอร์น็อค 1"); e("KLOPF_ADC2", "แรงดันเซ็นเซอร์น็อค 2");
        e("KR_ADAP", "ค่าเรียนรู้น็อค"); e("DFMONITOR", "โหลดไดชาร์จ (DF)"); e("ZSI", "สัญญาณจุดระเบิด");
        e("DREHZAHL_E_LUEFTER_VON_AC", "รอบพัดลมไฟฟ้า (จากแอร์)"); e("E_LUEFTER", "พัดลมไฟฟ้า");
        e("CO_POT", "ค่าปรับ CO เดินเบา"); e("BETRIEBSSTUNDEN", "ชั่วโมงทำงาน"); e("BETRIEBSSTUNDENZAEHLER", "ชั่วโมงทำงาน");
        e("TATEIST", "Duty วาล์ว EVAP (TEV)"); e("RKAT", "ค่าเรียนรู้ส่วนผสม (เพิ่ม) B1"); e("RKAT2", "ค่าเรียนรู้ส่วนผสม (เพิ่ม) B2");
        e("MIL", "ไฟเช็คเอนจิ้น (MIL)"); e("STATUS_MIL", "ไฟเช็คเอนจิ้น (MIL)"); e("ANZAHL_MIL", "จำนวนโค้ดที่ทำให้ไฟเช็คติด");
        e("KM_MIL", "ระยะทางตั้งแต่ไฟเช็คติด"); e("KM_STAND_MIL_AN", "ระยะทางตั้งแต่ไฟเช็คติด");
        for (int i = 0; i <= 7; i++) e("RKRN" + i, "ค่าน็อค สูบลำดับ " + (i + 1));
        for (int i = 1; i <= 8; i++) e("LUTSFI" + i, "ค่าสั่น/Misfire สูบลำดับ " + i);
        for (int i = 1; i <= 6; i++) e("K_MW_" + i, "ค่าน็อค สูบ " + i);
        // ---------- ชื่อตัวแปร Bosch ME9 / MS45 / MEV9 ----------
        e("USVK", "แรงดัน O2 หน้าแคต B1"); e("USVK2", "แรงดัน O2 หน้าแคต B2");
        e("USHK", "แรงดัน O2 หลังแคต B1"); e("USHK2", "แรงดัน O2 หลังแคต B2");
        e("UPWG1", "แรงดันคันเร่ง ตัวที่ 1"); e("UPWG2", "แรงดันคันเร่ง ตัวที่ 2");
        e("UDKP1", "แรงดันลิ้นปีกผีเสื้อ ตัวที่ 1"); e("UDKP2", "แรงดันลิ้นปีกผีเสื้อ ตัวที่ 2"); e("UDKP1V", "แรงดันลิ้นปีกผีเสื้อ 1 (ดิบ)");
        e("UHFM", "แรงดันไฟ MAF"); e("WUB", "แรงดันแบตเตอรี่ (ADC)");
        e("WTMOT", "อุณหภูมิน้ำหล่อเย็น (ADC)"); e("WTANS", "อุณหภูมิอากาศเข้า (ADC)"); e("WTKA", "อุณหภูมิน้ำออกหม้อน้ำ (ADC)"); e("WTOL", "อุณหภูมิน้ำมันเครื่อง (ADC)");
        e("UHSV", "ฮีตเตอร์ O2 หน้าแคต B1"); e("UHSV2", "ฮีตเตอร์ O2 หน้าแคต B2"); e("UHSH", "ฮีตเตอร์ O2 หลังแคต B1"); e("UHSH2", "ฮีตเตอร์ O2 หลังแคต B2");
        e("DISA", "แรงดัน DISA"); e("UDDSS", "แรงดันเซ็นเซอร์ MAP"); e("UDSU", "แรงดันเซ็นเซอร์ความกดบรรยากาศ");
        e("VSAADP", "ค่าเรียนรู้ VANOS ไอเสีย B1"); e("VSAADP2", "ค่าเรียนรู้ VANOS ไอเสีย B2");
        e("VSEADP", "ค่าเรียนรู้ VANOS ไอดี B1"); e("VSEADP2", "ค่าเรียนรู้ VANOS ไอดี B2");
        for (int i = 0; i <= 3; i++) {
            e("VSAADPFL" + i, "ค่าเรียนรู้ VANOS ไอเสีย B1 ฟัน " + i); e("VSAADP2FL" + i, "ค่าเรียนรู้ VANOS ไอเสีย B2 ฟัน " + i);
            e("VSEADPFL" + i, "ค่าเรียนรู้ VANOS ไอดี B1 ฟัน " + i); e("VSEADP2FL" + i, "ค่าเรียนรู้ VANOS ไอดี B2 ฟัน " + i);
        }
        e("VSATV", "Duty VANOS ไอเสีย B1"); e("VSATV2", "Duty VANOS ไอเสีย B2"); e("VSETV", "Duty VANOS ไอดี B1"); e("VSETV2", "Duty VANOS ไอดี B2");
        e("VSASPRI", "VANOS ไอเสีย เป้าหมาย B1"); e("VSASPRI2", "VANOS ไอเสีย เป้าหมาย B2"); e("VSESPRI", "VANOS ไอดี เป้าหมาย B1"); e("VSESPRI2", "VANOS ไอดี เป้าหมาย B2");
        e("MDGEN", "แรงบิดภาระไดชาร์จ"); e("MDKO", "แรงบิดภาระคอมแอร์"); e("DMRLLR", "แรงบิดจากตัวคุมรอบเดินเบา"); e("DMLLRI", "แรงบิดคุมเดินเบา (I)");
        e("DWKR", "องศาที่ถอยจากน็อค (เฉลี่ย)"); e("TUMG", "อุณหภูมิภายนอก"); e("FHO", "ค่าชดเชยความสูง");
        e("DLAHI", "ค่าปรับ Lambda จาก O2 หลังแคต B1"); e("DLAHI2", "ค่าปรับ Lambda จาก O2 หลังแคต B2");
        e("FRA", "ค่าเรียนรู้ส่วนผสม (คูณ) B1"); e("FRA2", "ค่าเรียนรู้ส่วนผสม (คูณ) B2");
        e("LAMSONI", "AFR อากาศ:น้ำมัน B1"); e("LAMSONI2", "AFR อากาศ:น้ำมัน B2");
        e("RLSOL", "โหลดเป้าหมาย"); e("TE2", "เวลาฉีดน้ำมัน B2"); e("WPED", "ตำแหน่งคันเร่ง (%)"); e("ZWIST", "องศาจุดระเบิดจริง");
        e("MIMIN", "แรงบิดต่ำสุด"); e("NSOLFASTA", "รอบเดินเบาเป้าหมาย"); e("WDKBAFASTA", "มุมลิ้นปีกผีเสื้อ");
        e("EVHUBI", "Valvetronic ระยะยกวาล์ว จริง"); e("EVHUBI2", "Valvetronic ระยะยกวาล์ว จริง B2"); e("EVHUBS", "Valvetronic ระยะยกวาล์ว เป้าหมาย");
        e("EXWINKI", "Valvetronic มุมเพลาเยื้องศูนย์ จริง"); e("EXWINKI2", "Valvetronic มุมเพลาเยื้องศูนย์ จริง B2"); e("EXWINKS", "Valvetronic มุมเพลาเยื้องศูนย์ เป้าหมาย");
        e("VVTSW", "Valvetronic มุมเป้าหมาย"); e("VVTIW", "Valvetronic มุมจริง"); e("VVTTV", "Valvetronic Duty"); e("VVTSW2", "Valvetronic มุมเป้าหมาย B2"); e("VVTIW2", "Valvetronic มุมจริง B2"); e("VVTTV2", "Valvetronic Duty B2");
        e("MIL_EIN", "ไฟเช็คเอนจิ้นติด"); e("KL15_EIN", "กุญแจ ON (KL15)"); e("EKP_EIN", "ปั๊มติ๊กทำงาน"); e("SLP_EIN", "ปั๊มลม Secondary air ทำงาน"); e("SLV_EIN", "วาล์ว Secondary air ทำงาน");
        e("HSVE_EIN", "ฮีตเตอร์ O2 หน้าแคต B1 ทำงาน"); e("HSVE2_EIN", "ฮีตเตอร์ O2 หน้าแคต B2 ทำงาน"); e("HSHE_EIN", "ฮีตเตอร์ O2 หลังแคต B1 ทำงาน"); e("HSHE2_EIN", "ฮีตเตอร์ O2 หลังแคต B2 ทำงาน");
        e("AKR_EIN", "วาล์วท่อไอเสียทำงาน"); e("EBL_EIN", "พัดลมกล่อง ECU ทำงาน"); e("ETR_EIN", "เทอร์โมสตัทไฟฟ้าทำงาน"); e("STA_EIN", "รีเลย์สตาร์ททำงาน"); e("LLK_EIN", "พัดลมไฟฟ้าทำงาน");
        e("UBAT", "แรงดันแบตเตอรี่"); e("KVA", "ปริมาณน้ำมันที่ใช้"); e("CO_ABGLEICH", "ค่าปรับ CO"); e("MOTORTEMPERATUR_ADC", "อุณหภูมิน้ำหล่อเย็น (ADC)");
        e("PWG1_WINKEL", "มุมคันเร่ง ตัวที่ 1"); e("PWG2_WINKEL", "มุมคันเร่ง ตัวที่ 2"); e("DK1_WINKEL", "มุมลิ้นปีกผีเสื้อ ตัวที่ 1"); e("DK2_WINKEL", "มุมลิ้นปีกผีเสื้อ ตัวที่ 2");
        e("MDK1_WINKEL", "มุมลิ้นปีกผีเสื้อ ตัวที่ 1"); e("MDK2_WINKEL", "มุมลิ้นปีกผีเสื้อ ตัวที่ 2");
        e("LAMBDA_INTEGRATOR_1_MWB", "Lambda correction (STFT) B1"); e("LAMBDA_INTEGRATOR_2_MWB", "Lambda correction (STFT) B2");
        e("KLOPFEN", "ค่าน็อค"); e("EWS_LOCK", "สถานะล็อค EWS"); e("ZEIT", "เวลา"); e("DAUER", "ระยะเวลา");
        for (int i = 1; i <= 6; i++) e("ZYL" + i, "สูบ " + i);
        e("MISSFIRE", "Readiness: Misfire"); e("FUELSYSTEM", "Readiness: ระบบน้ำมัน"); e("COMPREHENSIVE", "Readiness: อุปกรณ์รวม");
        e("KAT_UEBERWACHUNG", "Readiness: แคต"); e("EVAPORATE", "Readiness: EVAP"); e("SEC_AIR", "Readiness: Secondary air"); e("EGR", "Readiness: EGR");
        e("UBW_KOMP", "ตรวจสอบ: อุปกรณ์รวม"); e("UBW_KVS", "ตรวจสอบ: ระบบน้ำมัน"); e("UBW_AUS", "ตรวจสอบ: Misfire"); e("UBW_LSH", "ตรวจสอบ: ฮีตเตอร์ O2");
        e("UBW_LS", "ตรวจสอบ: เซ็นเซอร์ O2"); e("UBW_SLS", "ตรวจสอบ: Secondary air"); e("UBW_TES", "ตรวจสอบ: รั่วถังน้ำมัน"); e("UBW_KKV", "ตรวจสอบ: แคต");
        e("RDY_LSH", "Readiness: ฮีตเตอร์ O2"); e("RDY_LS", "Readiness: เซ็นเซอร์ O2"); e("RDY_SLS", "Readiness: Secondary air"); e("RDY_TES", "Readiness: รั่วถังน้ำมัน"); e("RDY_KKV", "Readiness: แคต");
        e("KICKDOWN", "Kick-down"); e("FAHRSTUFE", "ตำแหน่งเกียร์ (P/R/N/D)"); e("BREMSE_AKTIV", "เหยียบเบรก"); e("KUPPLUNGSSCHALTER", "สวิตช์คลัตช์");
        e("STEGDREHZAHL", "รอบเฟืองสุริยะ (Steg)"); e("UBAT", "แรงดันแบตเตอรี่"); e("OELALTERUNG", "อายุน้ำมันเกียร์");
        e("RADDREHZAHL_VL", "รอบล้อหน้าซ้าย"); e("RADDREHZAHL_VR", "รอบล้อหน้าขวา"); e("RADDREHZAHL_HL", "รอบล้อหลังซ้าย"); e("RADDREHZAHL_HR", "รอบล้อหลังขวา");
        for (int i = 1; i <= 6; i++) e("EDS" + i, "วาล์วแรงดัน EDS " + i);
        e("PROG_TASTER", "ปุ่มโหมดเกียร์"); e("PROG_MODUS", "โหมดเกียร์"); e("SCHALTUNGSART_LETZTE", "การเปลี่ยนเกียร์ครั้งล่าสุด"); e("SCHALTUNGSART_AKTUELL", "การเปลี่ยนเกียร์ปัจจุบัน");
        e("STEPTRONIC_ERK", "Steptronic"); e("GASSE_STEPTRONIC", "ช่อง Steptronic"); e("GETRIEBESCHALTER", "สวิตช์ตำแหน่งเกียร์"); e("SCHRITTSCHALTUNG", "เปลี่ยนเกียร์ Steptronic");
        e("DKG", "ลิ้นปีกผีเสื้อ (จาก DME)"); e("KTEST_HFM", "ทดสอบ MAF"); e("KTEST_DREHZAHL", "ทดสอบรอบ");
        // ---------- เกียร์ ----------
        e("GETRIEBEOELTEMPERATUR", "อุณหภูมิน้ำมันเกียร์"); e("OELTEMPERATUR", "อุณหภูมิน้ำมันเกียร์"); e("GETRIEBETEMPERATUR", "อุณหภูมิน้ำมันเกียร์");
        e("TURBINENDREHZAHL", "รอบเทอร์ไบน์ (อินพุตเกียร์)"); e("ABTRIEBSDREHZAHL", "รอบเพลาขาออก"); e("GANG", "เกียร์ปัจจุบัน");
        e("WAEHLHEBEL", "ตำแหน่งคันเกียร์"); e("WAEHLHEBELPOSITION", "ตำแหน่งคันเกียร์"); e("FAHRPROGRAMM", "โหมดขับ");
        e("WANDLERSCHLUPF", "การลื่นทอร์คคอนเวอร์เตอร์"); e("SCHLUPF_WANDLER", "การลื่นทอร์คคอนเวอร์เตอร์");

        // ---------- คำย่อย (แปลทีละคำ) ----------
        t("STAT", ""); t("STATUS", ""); t("WERT", ""); t("MWB", ""); t("ERG", "");
        t("MOTOR", "เครื่อง"); t("DREHZAHL", "รอบ"); t("TEMPERATUR", "อุณหภูมิ"); t("TEMP", "อุณหภูมิ");
        t("OEL", "น้ำมัน"); t("KUEHLW", "น้ำหล่อเย็น"); t("KUEHLMITTEL", "น้ำหล่อเย็น"); t("AUSL", "ขาออก");
        t("AN", "ขาเข้า"); t("LUFT", "อากาศ"); t("LUFTTEMPERATUR", "อุณหภูมิอากาศ"); t("DRUCK", "แรงดัน");
        t("SPANNUNG", "แรงดันไฟ"); t("STROM", "กระแส"); t("CURRENT", "กระแส"); t("WINKEL", "มุม");
        t("DKP", "ลิ้นปีกผีเสื้อ"); t("DK", "ลิ้นปีกผีเสื้อ"); t("MDK", "มอเตอร์ลิ้นเร่ง"); t("PWG", "คันเร่ง"); t("POTI", "ตัวต้านทาน");
        t("LMM", "MAF"); t("HFM", "MAF"); t("MASSE", "มวล"); t("LAMBDA", "แลมบ์ดา"); t("SONDE", "เซ็นเซอร์ O2");
        t("LS", "O2"); t("VKAT", "หน้าแคต"); t("NKAT", "หลังแคต"); t("HEIZUNG", "ฮีตเตอร์"); t("TV", "Duty");
        t("VANOS", "VANOS"); t("NW", "แคม"); t("EINLASS", "ไอดี"); t("AUSLASS", "ไอเสีย"); t("IST", "จริง"); t("SOLL", "เป้าหมาย");
        t("REF", "อ้างอิง"); t("ADAPTION", "ค่าเรียนรู้"); t("ADAPT", "ค่าเรียนรู้"); t("ADAP", "ค่าเรียนรู้"); t("LL", "เดินเบา");
        t("STELLER", "ตัวปรับ"); t("KLOPF", "น็อค"); t("GESCHWINDIGKEIT", "ความเร็ว"); t("UBATT", "แบตเตอรี่"); t("UB", "แบตเตอรี่");
        t("LAST", "โหลด"); t("EIN", "(เปิด/ปิด)"); t("AUS", "ปิด"); t("KO", "คอมแอร์"); t("AC", "แอร์"); t("KLIMA", "แอร์");
        t("LUEFTER", "พัดลม"); t("TANK", "ถังน้ำมัน"); t("KAT", "แคต"); t("BANK1", "B1"); t("BANK2", "B2"); t("BANK", "Bank");
        t("ZYL", "สูบ"); t("ROHWERT", "ค่าดิบ"); t("ROHWERT1", "ค่าดิบ 1"); t("ROHWERT2", "ค่าดิบ 2"); t("ADC", "(ADC)");
        t("INTEGRATOR", "อินทิเกรเตอร์"); t("KUPPLUNG", "คลัตช์"); t("KUP", "คลัตช์"); t("KUPPL", "คลัตช์");
        t("BREMS", "เบรก"); t("BLS", "สวิตช์ไฟเบรก"); t("BLTS", "สวิตช์ทดสอบไฟเบรก"); t("FGR", "ครูซคอนโทรล"); t("TEMPOMAT", "ครูซคอนโทรล");
        t("SCHUB", "ถอนคันเร่ง"); t("SCHUBAB", "ตัดน้ำมันตอนถอนคันเร่ง"); t("START", "สตาร์ท"); t("VL", "เร่งเต็ม"); t("TL", "เร่งบางส่วน");
        t("LAMBDAREGELUNG", "ควบคุมแลมบ์ดา"); t("REGELUNG", "การควบคุม"); t("EGS", "เกียร์ออโต้"); t("ASC", "ASC"); t("DSC", "DSC");
        t("EINGRIFF", "แทรกแซง"); t("SLV", "วาล์วปั๊มลม"); t("SLP", "ปั๊มลม Secondary air"); t("SEK", "Secondary"); t("TEV", "วาล์ว EVAP");
        t("EKP", "ปั๊มติ๊ก"); t("ANSTEUERUNG", "สั่งงาน"); t("KATHEIZEN", "อุ่นแคต"); t("FEHLERLAMPE", "ไฟเตือน"); t("EML", "EML");
        t("DISA", "DISA"); t("DMTL", "ปั๊มตรวจรั่ว DMTL"); t("GEBERRAD", "ล้อสัญญาณข้อเหวี่ยง"); t("OK", "OK");
        t("SPG", "แรงดันไฟ"); t("UPWG", "แรงดันคันเร่ง"); t("ZUENDWINKEL", "องศาจุดระเบิด"); t("EINSPRITZZEIT", "เวลาฉีด");
        t("MAX", "สูงสุด"); t("MIN", "ต่ำสุด"); t("MITTELWERT", "ค่าเฉลี่ย"); t("ZAEHLER", "ตัวนับ"); t("FEHLER", "ผิดพลาด");
        t("GETRIEBE", "เกียร์"); t("TURBINE", "เทอร์ไบน์"); t("TURBINEN", "เทอร์ไบน์"); t("ABTRIEB", "เพลาขาออก"); t("GANG", "เกียร์");
        t("WAEHLHEBEL", "คันเกียร์"); t("PROGRAMM", "โหมด"); t("WANDLER", "ทอร์คคอนเวอร์เตอร์"); t("MV", "โซลินอยด์");
        t("EDS", "วาล์วแรงดัน EDS"); t("SCHLUPF", "การลื่น"); t("POSITION", "ตำแหน่ง"); t("SCHALTUNG", "การเปลี่ยนเกียร์");
        t("ADAPTIONSWERT", "ค่าเรียนรู้"); t("RAD", "ล้อ"); t("KICKDOWN", "Kick-down"); t("KD", "Kick-down"); t("SPORT", "Sport");
        t("VORNE", "หน้า"); t("MV1", "โซลินอยด์ 1"); t("MV2", "โซลินอยด์ 2"); t("MV3", "โซลินอยด์ 3"); t("MVSL", "โซลินอยด์ Shift-lock"); t("BREMSSIGNAL", "สัญญาณเบรก"); t("NOTPROGRAMM", "โหมดฉุกเฉิน"); t("NOTLAUF", "โหมดฉุกเฉิน"); t("MAN", "แมนนวล"); t("GASSE", "ช่อง"); t("FAHRSTUFE", "ตำแหน่งเกียร์"); t("ABGASKLAPPE", "วาล์วท่อไอเสีย"); t("A_KLAPPE", "วาล์วท่อไอเสีย"); t("CAN", "CAN"); t("MONITOR", "(รองรับ)"); t("DIAGNOSEWERTE", "ค่าวินิจฉัย"); t("REGELFREQUENZ", "ความถี่คุม"); t("FETT", "หนา"); t("MAGER", "บาง"); t("RADDREHZAHL", "รอบล้อ"); t("NR", "เลข"); t("ANZAHL", "จำนวน"); t("BATTERIE", "แบตเตอรี่"); t("SIGNAL", "สัญญาณ"); t("HINTEN", "หลัง"); t("LINKS", "ซ้าย"); t("RECHTS", "ขวา");
    }

    /** ตัด STAT_/STATUS_ และ _WERT/_MWB ออก */
    public static String base(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        n = n.replaceAll("^(STATUS|STAT)_", "");
        n = n.replaceAll("_(WERT|MWB)$", "");
        n = n.replaceAll("_(WERT|MWB)$", "");
        return n;
    }

    public static String label(String resultName) {
        String b = base(resultName);
        String x = EXACT.get(b);
        if (x != null) return x;
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("^(.*?[A-Z])(\\d)$").matcher(b);
        if (dm.find() && EXACT.containsKey(dm.group(1))) return EXACT.get(dm.group(1)) + " #" + dm.group(2);
        String[] parts = b.split("_");
        List<String> out = new ArrayList<>();
        int known = 0;
        for (int i = 0; i < parts.length; i++) {
            // ลองจับคู่ 3 / 2 คำติดกันก่อน
            String hit = null;
            int used = 1;
            for (int n = Math.min(3, parts.length - i); n >= 1 && hit == null; n--) {
                StringBuilder sb = new StringBuilder(parts[i]);
                for (int j = 1; j < n; j++) sb.append('_').append(parts[i + j]);
                String k = sb.toString();
                if (n > 1 && EXACT.containsKey(k)) { hit = EXACT.get(k); used = n; }
                else if (TOK.containsKey(k)) { hit = TOK.get(k); used = n; }
            }
            if (hit != null) {
                known++;
                if (!hit.isEmpty()) out.add(hit);
                i += used - 1;
            } else if (parts[i].matches("\\d+")) {
                out.add(parts[i]);
            } else {
                out.add(parts[i]);
            }
        }
        if (known == 0) return resultName;
        return String.join(" ", out).replaceAll("\\s+", " ").trim();
    }

    public static String unit(String u) {
        if (u == null) return "";
        String s = u.trim();
        if (s.equalsIgnoreCase("Grad C") || s.equalsIgnoreCase("Grad_C") || s.equals("C")) return "°C";
        if (s.equalsIgnoreCase("1/min") || s.equalsIgnoreCase("U/min") || s.equalsIgnoreCase("min-1") || s.equalsIgnoreCase("1/min ") || s.equalsIgnoreCase("rpm")) return "rpm";
        if (s.equalsIgnoreCase("Grad KW") || s.equalsIgnoreCase("Grad_KW") || s.equalsIgnoreCase("Grad")) return "°";
        if (s.equalsIgnoreCase("kg/h")) return "kg/h";
        if (s.equalsIgnoreCase("km/h")) return "km/h";
        if (s.equals("-")) return "";
        return s;
    }
}
