package com.tms.bmwediag.io;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * ไดรเวอร์ FTDI (FT232R) แบบเขียนเองผ่าน Android USB Host API
 * — สาย K+DCAN / INPA ส่วนใหญ่ใช้ชิปนี้
 */
public class FtdiSerial implements SerialPort {
    private static final int REQ_RESET = 0, REQ_MODEM_CTRL = 1, REQ_FLOW = 2,
            REQ_BAUD = 3, REQ_DATA = 4, REQ_LATENCY = 9;
    private static final int OUT = 0x40;
    private static final int TIMEOUT = 1000;

    private final UsbManager manager;
    private final UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn, epOut;
    private int dataValue = 8 | (PARITY_NONE << 8);
    private final int index = 1; // ช่อง A
    private final byte[] rx = new byte[512];
    // บัฟเฟอร์ข้อมูลที่อ่านเกินมา
    private final byte[] pending = new byte[4096];
    private int pendLen = 0;

    public FtdiSerial(UsbManager manager, UsbDevice device) {
        this.manager = manager;
        this.device = device;
    }

    @Override
    public void open() throws IOException {
        conn = manager.openDevice(device);
        if (conn == null) throw new IOException("เปิดอุปกรณ์ USB ไม่ได้ (ยังไม่ได้อนุญาต?)");
        intf = device.getInterface(0);
        if (!conn.claimInterface(intf, true)) throw new IOException("claimInterface ล้มเหลว");
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep; else epOut = ep;
        }
        if (epIn == null || epOut == null) throw new IOException("ไม่พบ bulk endpoint");
        ctrl(REQ_RESET, 0, index);
        ctrl(REQ_FLOW, 0, index);
        ctrl(REQ_LATENCY, 2, index); // latency 2ms ให้ตอบสนองเร็ว
    }

    @Override
    public void close() {
        try { if (conn != null) { conn.releaseInterface(intf); conn.close(); } } catch (Exception ignored) {}
        conn = null;
    }

    private void ctrl(int req, int value, int idx) throws IOException {
        int r = conn.controlTransfer(OUT, req, value, idx, null, 0, TIMEOUT);
        if (r < 0) throw new IOException("FTDI control transfer ล้มเหลว req=" + req);
    }

    @Override
    public void setParameters(int baud, int dataBits, int parity, int stopBits) throws IOException {
        int[] b = FtdiBaud.encode(baud);
        // ชิป single-channel (FT232R/BM): wIndex = บิตที่ 16 ของ divisor (ไม่มีเลขพอร์ต)
        // ชิป multi-channel (FT2232/4232): wIndex = (bit16 << 8) | port
        boolean multi = device.getInterfaceCount() > 1;
        ctrl(REQ_BAUD, b[0], multi ? ((b[1] << 8) | index) : b[1]);
        int stop = stopBits == 2 ? 2 : 0;
        dataValue = (dataBits & 0xFF) | ((parity & 7) << 8) | (stop << 11);
        ctrl(REQ_DATA, dataValue, index);
    }

    @Override
    public void setBreak(boolean on) throws IOException {
        ctrl(REQ_DATA, on ? (dataValue | (1 << 14)) : dataValue, index);
    }

    @Override
    public void setDtr(boolean on) throws IOException { ctrl(REQ_MODEM_CTRL, on ? 0x0101 : 0x0100, index); }

    @Override
    public void setRts(boolean on) throws IOException { ctrl(REQ_MODEM_CTRL, on ? 0x0202 : 0x0200, index); }

    @Override
    public void write(byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            int n = Math.min(data.length - off, epOut.getMaxPacketSize());
            byte[] chunk = new byte[n];
            System.arraycopy(data, off, chunk, 0, n);
            int w = conn.bulkTransfer(epOut, chunk, n, TIMEOUT);
            if (w < 0) throw new IOException("เขียน USB ล้มเหลว");
            off += w;
        }
    }

    @Override
    public int read(byte[] buf, int timeoutMs) throws IOException {
        if (pendLen > 0) return takePending(buf);
        long end = System.currentTimeMillis() + Math.max(1, timeoutMs);
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return 0;
            int n = conn.bulkTransfer(epIn, rx, rx.length, (int) Math.min(left, 50));
            if (n > 2) {
                // ทุก packet (ขนาด maxPacket) มี 2 ไบต์สถานะนำหน้า
                int mps = epIn.getMaxPacketSize();
                for (int p = 0; p < n; p += mps) {
                    int len = Math.min(mps, n - p) - 2;
                    if (len > 0 && pendLen + len <= pending.length) {
                        System.arraycopy(rx, p + 2, pending, pendLen, len);
                        pendLen += len;
                    }
                }
                if (pendLen > 0) return takePending(buf);
            }
        }
    }

    private int takePending(byte[] buf) {
        int n = Math.min(buf.length, pendLen);
        System.arraycopy(pending, 0, buf, 0, n);
        System.arraycopy(pending, n, pending, 0, pendLen - n);
        pendLen -= n;
        return n;
    }

    @Override
    public void purge() throws IOException {
        pendLen = 0;
        ctrl(REQ_RESET, 1, index); // purge RX
        ctrl(REQ_RESET, 2, index); // purge TX
        // ล้างข้อมูลค้างใน endpoint
        for (int i = 0; i < 4; i++) {
            int n = conn.bulkTransfer(epIn, rx, rx.length, 5);
            if (n <= 2) break;
        }
    }
}
