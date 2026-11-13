import com.tms.bmwediag.diag.*;
import com.tms.bmwediag.io.*;
import com.tms.bmwediag.proto.*;
import java.nio.file.*;
import java.util.*;

public class KwpSessionTest {
    /** DME จำลองแบบ KWP2000* (9600 8E1, addr 0x12) */
    static class Port implements SerialPort {
        final ArrayDeque<Byte> q=new ArrayDeque<>(); int baud,parity; final List<String> rx=new ArrayList<>();
        public void open(){} public void close(){}
        public void setParameters(int b,int d,int p,int s){baud=b;parity=p;}
        public void setBreak(boolean on){} public void setDtr(boolean o){} public void setRts(boolean o){}
        public synchronized void write(byte[] d){
            for(byte b:d) q.add(b);
            if(d.length<5 || (d[0]&0xFF)<0x80 || (d[1]&0xFF)!=0x12) return; // ไม่ใช่ KWP2000* ถึง DME
            int len=d[3]&0xFF; byte[] data=Arrays.copyOfRange(d,4,4+len);
            rx.add(Hex.of(data));
            int sid=data[0]&0xFF; byte[] resp;
            if(sid==0x1A) resp=Hex.parse("5A 80 00 00 07 55 79 35 35 31 30 34 30 31 35 31 36 37 31 31 32 31 35 33 30 35 31 32");
            else if(sid==0x18) resp=Hex.parse("58 02 27 10 24 29 CC E4");
            else if(sid==0x14) resp=Hex.parse("54 FF FF");
            else { resp=new byte[Math.min(60, 2+40)]; resp[0]=(byte)(sid+0x40); if(data.length>1) resp[1]=data[1]; }
            byte[] f=new byte[resp.length+5]; f[0]=(byte)0x80; f[1]=(byte)0xF1; f[2]=0x12; f[3]=(byte)resp.length;
            System.arraycopy(resp,0,f,4,resp.length); f[f.length-1]=(byte)KLine.xor(f,0,f.length-1);
            for(byte b:f) q.add(b);
        }
        public synchronized int read(byte[] buf,int t){ int n=0; while(!q.isEmpty()&&n<buf.length) buf[n++]=q.poll(); return n; }
        public synchronized void purge(){ q.clear(); }
    }
    public static void main(String[] a) throws Exception {
        List<EcuDef> defs=DefinitionLoader.parse(new String(Files.readAllBytes(Paths.get("app/src/main/assets/ecus.json")),"UTF-8"));
        for (String id : new String[]{"me92","ms45"}) {
            EcuDef ecu=null; for(EcuDef e:defs) if(e.id.equals(id)) ecu=e;
            DiagSession.Options o=new DiagSession.Options();
            o.sgbdFiles = name -> { for(Path x: Files.newDirectoryStream(Paths.get("app/src/main/assets/sgbd"))) if(x.getFileName().toString().equalsIgnoreCase(name)) return Files.readAllBytes(x); return null; };
            DiagSession s=new DiagSession(ecu,o,m->{ if(!m.startsWith("TX")&&!m.startsWith("RX")) System.out.println("LOG "+m);});
            Port p=new Port();
            s.connectSerial(p);
            System.out.println("== "+id+" SGBD="+s.isSgbd()+" "+s.describe()+" baud="+p.baud+" parity="+p.parity);
            for(String l: s.readIdent()) System.out.println("  "+l);
            for(Dtc.Code c: s.readFaults()) System.out.println("  FAULT "+c.code+" "+c.text.replace("\n"," | "));
            System.out.println("  "+s.clearFaults());
            List<DiagSession.Reading> r=s.pollLive(); System.out.println("  live "+r.size()+" values"+(r.isEmpty()?"":", e.g. "+r.get(0).name+"="+r.get(0).text+" "+r.get(0).unit));
            System.out.println("  requests: "+p.rx.subList(0,Math.min(12,p.rx.size())));
        }
    }
}
