# BMW DIAG (Beta test) — By TMS

แอป Android อ่าน/ลบโค้ด fault และดู data สด สำหรับ

| กล่อง | รถ | สาย/อุปกรณ์ | SGBD ที่ใช้ |
|---|---|---|---|
| DME MS42 | M52TU / M54 รุ่นแรก | K+DCAN USB (K-line) | MS420DS0 (DS2) |
| DME MS43 | M54 E46/E39/E53/E83 | K+DCAN USB (K-line) | MS430DS0 (DS2) |
| DME ME9.2 | N42/N46 E46 | K+DCAN USB (K-line) | ME9K_NG4 (KWP2000*) |
| DME MS45 | M54 รุ่นหลัง | K+DCAN USB (K-line) | MS450DS0 (KWP2000*) |
| DME MEV9.2 | N46 E90/E87 | ELM327 Bluetooth (D-CAN) | MEV9N46 (KWP2000*) |
| เกียร์ GS20 | A5S360/390 | K+DCAN USB | GS20 (DS2) |
| เกียร์ GS8.60.4 | ZF 5HP19 | K+DCAN USB | GS8604 (DS2) |

- **SGBD ภาษาไทยฝังในแอปแล้ว** (`app/src/main/assets/sgbd/`) ไม่ต้องตั้งค่า
  - ถ้าเลือกโฟลเดอร์ Ecu ในตั้งค่า แอปจะใช้ไฟล์ในโฟลเดอร์ก่อน
  - ถ้า SGBD ใช้ไม่ได้: DME ใช้ OBD2 มาตรฐาน (PID + Pxxxx) / เกียร์ใช้ DS2 แบบในตัว
- data สด: แดชบอร์ด → **☰ เลือกค่าที่จะอ่าน** เลือกทีละค่า (ชื่อภาษาไทย) กด "ค่าแนะนำ" เพื่อกลับค่าเริ่มต้น — ยิ่งเลือกน้อยยิ่งอ่านเร็ว
- ข้อมูลกล่อง: แสดง VIN ที่เก็บในกล่อง (AIF) ด้านบน
- แอปเซ็นด้วย keystore คงที่ (`app/tms-beta.jks`) → ติดตั้งเวอร์ชันใหม่ทับได้เลย

## Build
Push ขึ้น GitHub → Actions → **Build APK** → ดาวน์โหลด artifact `BMW_DIAG_beta-apk`

## License
ตัวรัน SGBD พอร์ตจาก EdiabasLib (GPLv3) — ดู `NOTICE.md`. ไม่มีไฟล์ SGBD ของ BMW อยู่ในแอป
