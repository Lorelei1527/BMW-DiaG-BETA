# BMW E Diag — แอป Android อ่านข้อมูล BMW E-series

อ่าน live data, อ่าน/ลบโค้ด fault, อ่านข้อมูลกล่อง และบันทึก datalog เป็น CSV

## รองรับ

| กล่อง | โปรโตคอล | สาย K+DCAN (USB OTG) | ELM327 Bluetooth |
|---|---|---|---|
| OBD2 มาตรฐาน (DME ทุกรุ่น) | ISO14230 fast / ISO9141 5-baud / CAN | ✅ | ✅ |
| DME MS43 (M54) | DS2 9600 8E1 @0x12 | ✅ | ❌ (ELM ทำ DS2 ไม่ได้) |
| DME MS45 / ME9.2 | KWP2000 @0x12 | ✅ | ✅ (ทดลอง) |
| เกียร์ GS20 / GS8.60.4 (5HP19) | DS2 @0x32 | ✅ | ❌ |

ฟังก์ชัน: แดชบอร์ด live data, Fault (อ่าน/ลบ), ข้อมูลกล่อง (Ident/VIN/CALID), Terminal ส่งคำสั่งดิบ, CSV log → `Download/BmwEDiag/`

## Build APK

**วิธี A — GitHub Actions (ไม่ต้องลงอะไร)**
1. สร้าง repo ใหม่บน GitHub แล้วอัปโหลดทั้งโฟลเดอร์นี้ (รวม `.github/`)
2. เข้าแท็บ **Actions** → workflow **Build APK** จะรันเอง (หรือกด *Run workflow*)
3. รอ 3–5 นาที → เปิด run ล่าสุด → ดาวน์โหลด artifact **BmwEDiag-apk** → แตก zip ได้ `BmwEDiag.apk`

**วิธี B — Android Studio**: File → Open โฟลเดอร์นี้ → Build → Build APK(s)

**วิธี C — command line** (มี Android SDK + JDK 17): `./gradlew assembleRelease`

## การใช้งาน

1. สาย K+DCAN: ต่อผ่าน USB OTG — E46/E39/E83 ตั้งสวิตช์สายเป็น **K-line** (ขา 7+8)
2. ELM327: จับคู่ Bluetooth ในมือถือก่อน (PIN 1234 / 0000)
3. บิดกุญแจ ON → เลือกช่องทาง + กล่อง → **เชื่อมต่อ**
4. ถ้าไม่ติด ดูแท็บ **Log** จะเห็น TX/RX ทุกไบต์

## เพิ่มค่า live data เอง (ecus.json)

ตั้งค่า → Export → แก้ไฟล์ → Import. ตัวอย่างรูปแบบ (offset/สูตรเป็นตัวอย่าง ไม่ใช่ค่าจริงของ MS43):

```json
{ "request": "0B 03", "skip": 1, "params": [
  { "name": "RPM", "unit": "rpm", "offset": 0, "length": 2, "factor": 1 },
  { "name": "Coolant", "unit": "°C", "offset": 5, "length": 1, "factor": 0.75, "add": -48 }
]}
```

- DS2 `skip:1` = ข้าม status `A0`; KWP `skip:2` = ข้าม service+id (เช่น `61 01`)
- `value = raw × factor + add` — ถ้าไม่ใส่ `params` จะแสดงทุกไบต์ดิบ (B00, B01…) ไว้หา offset
- MS45/ME9.2 ใช้ `23 [addr 3 ไบต์] [len]` (ReadMemoryByAddress) กับที่อยู่ RAM จาก XDF ได้

> offset ของ DS2 `0B 03` (MS43) และคำสั่ง EGS ในค่าเริ่มต้นยังไม่ได้ทดสอบกับรถจริง ให้ใช้โหมดไบต์ดิบตรวจสอบก่อน

## โครงสร้างโค้ด

- `io/FtdiSerial` — ไดรเวอร์ FTDI เขียนเอง (baud, 8E1, break สำหรับ init)
- `proto/` — `Ds2Channel`, `KwpChannel`, `Iso9141Channel`, `Elm327`, `KLine` (ตัด echo / fast init / 5-baud)
- `diag/` — `DiagSession`, `ObdPids`, `Dtc`, `DefinitionLoader`, `CsvLogger`
- `tests/CoreTest.java` — ทดสอบโปรโตคอลกับ ECU จำลอง

## โหมด SGBD (ไฟล์ .prg จริงของ EDIABAS)

แอปมีตัวรันไฟล์ SGBD (พอร์ตจาก EdiabasLib, GPLv3 — ดู `NOTICE.md`)

1. คัดลอกโฟลเดอร์ `C:\EC-APPS\Ediabas\Ecu` (หรือ `C:\EDIABAS\Ecu`) ลงมือถือ
2. แอป → ตั้งค่า → **โฟลเดอร์ SGBD** → เลือกโฟลเดอร์นั้น
3. เลือกโมดูล (เช่น ระบบไฟ 0xD0) → เชื่อมต่อด้วยสาย USB K+DCAN
4. แอปจะรัน `D_00D0.grp` → หา variant (เช่น LSZ_2) → ใช้ IDENT / FS_LESEN / FS_LOESCHEN / STATUS_* จาก SGBD จริง
5. แท็บ Terminal: พิมพ์ชื่อ job เช่น `FS_ZAEHLER` หรือ `STATUS_LESEN;arg`

รองรับ concept DS2 (โมดูล E46 ส่วนใหญ่) — KWP2000*/BMW-FAST ยังไม่รองรับ
