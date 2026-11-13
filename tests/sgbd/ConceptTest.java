import com.tms.bmwediag.sgbd.*;
import java.nio.file.*;
import java.util.*;
public class ConceptTest {
    static class Rec extends EdInterface {
        public void setCommParameter(long[] p){ StringBuilder sb=new StringBuilder(); for(long x:p) sb.append(String.format("%X ",x)); System.out.println("   xsetpar: "+sb); commParameter=p; }
        public void setCommAnswerLen(short[] l){ System.out.println("   xawlen: "+Arrays.toString(l)); }
        public byte[] transmit(byte[] r){ StringBuilder sb=new StringBuilder(); for(byte b:r) sb.append(String.format("%02X ",b)); System.out.println("   xsend: "+sb); return new byte[]{(byte)0x80,(byte)0xF1,0x12,0x02,0x50,0x00,0x00}; }
    }
    public static void main(String[] a) throws Exception {
        for (String f : a) {
            System.out.println("== "+f);
            try {
                Sgbd s=new Sgbd(f, Files.readAllBytes(Paths.get("/mnt/user-data/uploads/Ecu/"+f)));
                s.setInterface(new Rec());
                List<Map<String,Sgbd.Result>> r=s.executeJob("INFO","");
                System.out.println("   ok jobs="+s.jobNames().size()+" hasFS="+s.hasJob("FS_LESEN"));
            } catch(Exception e){ System.out.println("   ERR "+e.getMessage()); }
        }
    }
}
