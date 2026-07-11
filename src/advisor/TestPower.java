package advisor;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGraph;
import arc.struct.ObjectSet;

public class TestPower {
    public static void test(Building b) {
        ObjectSet<PowerGraph> grids = new ObjectSet<>();
        if (b.power != null) {
            grids.add(b.power.graph);
        }
        for (PowerGraph g : grids) {
            float prod = g.getPowerProduced();
            float cons = g.getPowerNeeded();
        }
    }
}
