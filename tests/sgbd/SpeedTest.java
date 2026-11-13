import com.tms.bmwediag.diag.*;
import com.tms.bmwediag.io.*;
import com.tms.bmwediag.proto.*;
import java.nio.file.*;
import java.util.*;

/** ทดสอบสาย K+DCAN: ทุกกล่อง (DS2 / KWP2000* / BMW-FAST D-CAN), echo อัตโนมัติ, แคชคำสั่งซ้ำ, โหมดเร็ว */
public class SpeedTest {
    static class Port implements SerialPort {
        final ArrayDeque<Byte> q=new ArrayDeque<>(); int baud,parity; final List<String> rx=new ArrayList<>();
        final int addr; final String mode; final boolean echo;
        Port(int addr,String mode,boolean echo){this.addr=addr;this.mode=mode;this.echo=echo;}
        public void open(){} public void close(){}
        public void setParameters(int b,int d,int p,int s){baud=b;parity=p;}
        public void setBreak(boolean on){} public void setDtr(boolean o){} public void setRts(boolean o){}
        public synchronized void write(byte[] d){
            if(echo) for(byte b:d) q.add(b);
            if(mode.equals("DS2")){
                if(baud!=9600||parity!=SerialPort.PARITY_EVEN||(d[0]&0xFF)!=addr||d.length<3) return;
                rx.add(Hex.of(Arrays.copyOfRange(d,2,d.length-1)));
                byte[] p=new byte[42]; p[0]=(byte)0xA0; for(int i=1;i<p.length;i++) p[i]=(byte)(i*7);
                byte[] f=new byte[p.length+3]; f[0]=d[0]; f[1]=(byte)f.length; System.arraycopy(p,0,f,2,p.length);
                f[f.length-1]=(byte)KLine.xor(f,0,f.length-1); for(byte b:f) q.add(b);
            } else { // BMW-FAST 115200 8N1 (สายโหมด D-CAN)
                if(baud!=115200||parity!=SerialPort.PARITY_NONE||(d[0]&0xC0)!=0x80||(d[1]&0xFF)!=addr) return;
                int len=d[0]&0x3F, off=3; if(len==0){len=d[3]&0xFF;off=4;}
                byte[] data=Arrays.copyOfRange(d,off,off+len); rx.add(Hex.of(data));
                int sid=data[0]&0xFF; byte[] resp;
                if(sid==0x1A) resp=Hex.parse("5A 80 00 00 07 55 79 35 35 31 30 34 30 31 35 31 36 37 31 31 32 31 35 33 30 35 31 32");
                else if(sid==0x18) resp=Hex.parse("58 02 27 10 24 29 CC E4");
                else if(sid==0x14) resp=Hex.parse("54 FF FF");
                else { resp=new byte[42]; resp[0]=(byte)(sid+0x40); if(data.length>1) resp[1]=data[1]; }
                byte[] f=new byte[resp.length+4]; f[0]=(byte)(0x80|resp.length); f[1]=(byte)0xF1; f[2]=(byte)addr;
                System.arraycopy(resp,0,f,3,resp.length); f[f.length-1]=(byte)KLine.sum(f,0,f.length-1);
                for(byte b:f) q.add(b);
            }
        }
        public synchronized int read(byte[] buf,int t){ int n=0; while(!q.isEmpty()&&n<buf.length) buf[n++]=q.poll(); if(n==0) try{Thread.sleep(Math.min(t,2));}catch(Exception e){} return n; }
        public synchronized void purge(){ q.clear(); }
    }
    static int fails=0;
    static void check(boolean ok,String m){ System.out.println((ok?"PASS ":"FAIL ")+m); if(!ok) fails++; }
    static EcuDef def(List<EcuDef> defs,String id){ for(EcuDef e:defs) if(e.id.equals(id)) return e; return null; }

    static void run(List<EcuDef> defs,String id,int addr,String mode,boolean echo,boolean fast) throws Exception {
        DiagSession.Options o=new DiagSession.Options();
        o.fast=fast;
        o.sgbdFiles = name -> { Path p=Paths.get("app/src/main/assets/sgbd", name.toLowerCase()); return Files.exists(p)?Files.readAllBytes(p):null; };
        DiagSession s=new DiagSession(def(defs,id),o,m->{});
        Port p=new Port(addr,mode,echo);
        long t0=System.currentTimeMillis();
        s.connectSerial(p);
        long tc=System.currentTimeMillis()-t0;
        String tag=id+(echo?"":" (ไม่มี echo)")+(fast?"":" (โหมดปกติ)");
        check(s.isSgbd(), tag+": เชื่อมต่อ SGBD "+s.describe()+" ใช้ "+tc+" ms");
        if(!s.isSgbd()) return;
        List<DiagSession.Reading> r=s.pollLive();
        int before=p.rx.size(); long t1=System.currentTimeMillis();
        int N=5; for(int i=0;i<N;i++) r=s.pollLive();
        long per=(System.currentTimeMillis()-t1)/N; int req=(p.rx.size()-before)/N;
        int ok=0; for(DiagSession.Reading x:r) if(x.value!=null) ok++;
        check(ok==r.size() && r.size()>0, tag+": live "+r.size()+" ค่า อ่านได้ "+ok+" | ส่ง "+req+" คำสั่ง/รอบ | "+per+" ms/รอบ | เช่น "+r.get(0).name+"="+r.get(0).text+" "+r.get(0).unit);
        check(!s.readFaults().isEmpty(), tag+": อ่านโค้ด");
    }
    public static void main(String[] a) throws Exception {
        List<EcuDef> defs=DefinitionLoader.parse(new String(Files.readAllBytes(Paths.get("app/src/main/assets/ecus.json")),"UTF-8"));
        run(defs,"ms42",0x12,"DS2",true,true);
        run(defs,"ms42",0x12,"DS2",true,false);
        run(defs,"ms42",0x12,"DS2",false,true);
        run(defs,"ms43",0x12,"DS2",true,true);
        run(defs,"gs20",0x32,"DS2",true,true);
        run(defs,"gs8604",0x32,"DS2",true,true);
        run(defs,"mev92",0x12,"FAST",true,true);
        run(defs,"mev92",0x12,"FAST",false,true);
        System.out.println(fails==0?"\nALL PASSED":"\n"+fails+" FAILED");
    }
}
