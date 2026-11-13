package android.hardware.usb;
public class UsbDeviceConnection { public boolean claimInterface(UsbInterface i, boolean f){return true;} public boolean releaseInterface(UsbInterface i){return true;} public void close(){} public int controlTransfer(int a,int b,int c,int d,byte[] e,int f,int g){return 0;} public int bulkTransfer(UsbEndpoint e, byte[] b, int l, int t){return 0;} }
