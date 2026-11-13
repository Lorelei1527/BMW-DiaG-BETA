import com.tms.bmwediag.diag.*;
import com.tms.bmwediag.io.*;
import com.tms.bmwediag.proto.*;
import java.nio.file.*;
import java.util.*;

public class SessionTest {
    static class Port implements SerialPort {
        final ArrayDeque<Byte> q=new ArrayDeque<>(); int baud,parity;
        public void open(){} public void close(){}
        public void setParameters(int b,int d,int p,int s){baud=b;parity=p;}
        public void setBreak(boolean on){} public void setDtr(boolean o){} public void setRts(boolean o){}
        public synchronized void write(byte[] d){
            for(byte b:d) q.add(b);
            String k=Hex.of(d);
            String r=null;
            if(k.equals("D0 04 00 D4")) r="A0 06 92 50 82 22 34 30 00 20 03 09 35";
            else if(k.startsWith("D0 05 04 00")) r="A0 01";
            else if(k.startsWith("D0 05 04 01")) r="A0 47 21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00";
            else if(k.startsWith("D0 05 04")) r="A0 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00";
            else if(k.startsWith("D0 04 05")||k.startsWith("D0 05 05")) r="A0";
            else if(k.startsWith("D0")) r="A0 12 34 56 78 9A BC DE F0 11 22 33 44 55 66 77 88 99 AA BB CC DD EE";
            if(r==null) return;
            byte[] p=Hex.parse(r); byte[] f=new byte[p.length+3]; f[0]=d[0]; f[1]=(byte)f.length; System.arraycopy(p,0,f,2,p.length);
            f[f.length-1]=(byte)KLine.xor(f,0,f.length-1); for(byte b:f) q.add(b);
        }
        public synchronized int read(byte[] buf,int t){ int n=0; while(!q.isEmpty()&&n<buf.length) buf[n++]=q.poll(); return n; }
        public synchronized void purge(){ q.clear(); }
    }
    public static void main(String[] a) throws Exception {
        List<EcuDef> defs=DefinitionLoader.parse(new String(Files.readAllBytes(Paths.get("app/src/main/assets/ecus.json")),"UTF-8"));
        EcuDef lcm=null; for(EcuDef e:defs) if(e.id.equals("lcm")) lcm=e;
        DiagSession.Options o=new DiagSession.Options();
        o.sgbdFiles = name -> { Path p=Paths.get("/mnt/user-data/uploads/Ecu", name); if(!Files.exists(p)){ for(Path x: Files.newDirectoryStream(p.getParent())) if(x.getFileName().toString().equalsIgnoreCase(name)) return Files.readAllBytes(x); return null;} return Files.readAllBytes(p); };
        DiagSession s=new DiagSession(lcm,o,m->System.out.println("LOG "+m));
        s.connectSerial(new Port());
        System.out.println("SGBD mode: "+s.isSgbd()+"  "+s.describe());
        for(String l: s.readIdent()) System.out.println("  "+l);
        for(Dtc.Code c: s.readFaults()) System.out.println("  FAULT "+c.code+" "+c.text);
        System.out.println("  "+s.clearFaults());
        List<DiagSession.Reading> r=s.pollLive(); System.out.println("  live "+r.size()+" values, first: "+(r.isEmpty()?"-":r.get(0).name+"="+r.get(0).text));
        for(String l: s.runJob("FS_ZAEHLER")) System.out.println("  JOB "+l);
    }
}
