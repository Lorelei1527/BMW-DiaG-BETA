import com.tms.bmwediag.sgbd.*;
import java.nio.file.*;
import java.util.*;
public class GrpTest {
    public static void main(String[] a) throws Exception {
        Sgbd g=new Sgbd("D_00D0.grp", Files.readAllBytes(Paths.get("/mnt/user-data/uploads/Ecu/D_00D0.grp")));
        System.out.println(g.jobNames());
        SgbdTest.Sim sim=new SgbdTest.Sim(new HashMap<>(Map.of("D0 04 00","A0 06 92 50 82 22 34 30 00 20 03 09 35")));
        g.setInterface(sim);
        for (Map<String,Sgbd.Result> m : g.executeJob("IDENTIFIKATION","")) System.out.println(m.values());
        System.out.println(sim.log);
        Sgbd g2=new Sgbd("D_0080.grp", Files.readAllBytes(Paths.get("/mnt/user-data/uploads/Ecu/D_0080.grp")));
        System.out.println(g2.jobNames());
    }
}
