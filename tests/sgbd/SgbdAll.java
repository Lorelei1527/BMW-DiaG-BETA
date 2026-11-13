import com.tms.bmwediag.sgbd.*;
import java.nio.file.*;
import java.util.*;
public class SgbdAll {
    static class Sim extends EdInterface {
        public byte[] transmit(byte[] req) {
            int n=40; byte[] f=new byte[n]; f[0]=req[0]; f[1]=(byte)n; f[2]=(byte)0xA0;
            int cs=0; for(int i=0;i<n-1;i++) cs^=f[i]; f[n-1]=(byte)cs; return f;
        }
    }
    public static void main(String[] a) throws Exception {
        Map<String,Integer> errs=new TreeMap<>(); int ok=0,total=0;
        for (Path p : Files.newDirectoryStream(Paths.get(a.length>0?a[0]:"/mnt/user-data/uploads/Ecu"), "*.{prg,grp}")) {
            Sgbd s; try { s=new Sgbd(p.getFileName().toString(), Files.readAllBytes(p)); } catch(Exception e){ System.out.println(p+" LOAD "+e.getMessage()); continue; }
            s.setInterface(new Sim()); s.maxSteps=2_000_000;
            for (String j : s.jobNames()) {
                String u=j.toUpperCase();
                if (u.contains("SCHREIBEN")||u.contains("LOESCHEN")||u.startsWith("STEUERN")||u.contains("AUFTRAG")||u.contains("SLEEP")||u.contains("ENERGIE")||u.startsWith("C_")) continue;
                total++;
                try { s.executeJob(j,""); ok++; }
                catch(SgbdException e){ if(String.valueOf(e.getMessage()).contains("loop")) System.out.println("LOOP "+p.getFileName()+" "+j); String m=e.getMessage(); if(m==null)m="null"; m=m.replaceAll(" @[0-9A-F]+",""); errs.merge(m.length()>70?m.substring(0,70):m,1,Integer::sum); s.forceInit(); }
                catch(Throwable e){ errs.merge("JAVA "+e,1,Integer::sum); s.forceInit(); }
            }
        }
        System.out.println("jobs ok "+ok+"/"+total); errs.forEach((k,v)->System.out.println(v+"  "+k));
    }
}
