import com.tms.bmwediag.sgbd.*;
import java.nio.file.*;
import java.util.*;

public class SgbdTest {
    /** ECU จำลอง DS2: map request(hex ไม่รวม checksum) -> response data (หลัง addr/len ไม่รวม checksum) */
    static class Sim extends EdInterface {
        final Map<String,String> map; final List<String> log = new ArrayList<>();
        Sim(Map<String,String> m){map=m;}
        public byte[] transmit(byte[] req) throws SgbdException {
            StringBuilder sb=new StringBuilder(); for(byte b:req) sb.append(String.format("%02X ",b&0xFF));
            String k=sb.toString().trim(); log.add(k);
            String r=map.get(k);
            if(r==null && map.containsKey("*"+k.substring(0,Math.min(k.length(),8)))) r=map.get("*"+k.substring(0,8));
            if(r==null){ ediabas_setError(19); return null; }
            String[] p=r.split(" "); int addr=req[0]&0xFF;
            byte[] f=new byte[p.length+3]; f[0]=(byte)addr; f[1]=(byte)f.length;
            for(int i=0;i<p.length;i++) f[2+i]=(byte)Integer.parseInt(p[i],16);
            int cs=0; for(int i=0;i<f.length-1;i++) cs^=f[i]; f[f.length-1]=(byte)cs;
            return f;
        }
        void ediabas_setError(int c) throws SgbdException { SgbdBridge.setError(ediabas, c); }
    }
    static void dump(String title, List<Map<String,Sgbd.Result>> sets){
        System.out.println("== "+title+" sets="+sets.size());
        for(int i=0;i<sets.size();i++){ System.out.println(" ["+i+"] "+sets.get(i).values()); }
    }
    public static void main(String[] a) throws Exception {
        String dir="/mnt/user-data/uploads/Ecu/";
        // LSZ_2 IDENT with real LCM bytes
        Sgbd s=new Sgbd("LSZ_2.prg", Files.readAllBytes(Paths.get(dir+"LSZ_2.prg")));
        System.out.println(s.jobNames());
        Map<String,String> m=new HashMap<>();
        m.put("D0 04 00","A0 06 92 50 82 22 34 30 00 20 03 09 35");
        m.put("D0 05 04 00","A0 01");
        m.put("D0 05 04 01","A0 21 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00");
        m.put("*D0 05 04","A0 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00");
        Sim sim=new Sim(m); s.setInterface(sim);
        dump("IDENT", s.executeJob("IDENT",""));
        try { dump("FS_LESEN", s.executeJob("FS_LESEN","")); } catch(SgbdException e){ System.out.println("FS_LESEN err "+e.getMessage()); }
        System.out.println("telegrams: "+sim.log);
    }
}
