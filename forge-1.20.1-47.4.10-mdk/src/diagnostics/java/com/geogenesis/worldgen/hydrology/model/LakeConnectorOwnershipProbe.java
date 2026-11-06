package com.geogenesis.worldgen.hydrology.model;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;

/** Synthetic ownership contract only; production raw invariance is tested separately. */
public final class LakeConnectorOwnershipProbe {
    private LakeConnectorOwnershipProbe() { }

    public static void main(String[] args) throws Exception {
        Class<?> plan = Class.forName(ModelHydrologyProvider.class.getName() + "$ColumnPlan");
        Constructor<?> ctor = plan.getDeclaredConstructor(int.class, int.class, double.class,
                double.class, double.class, double.class, double.class, boolean.class,
                boolean.class, boolean.class, double.class);
        ctor.setAccessible(true);
        Method publish = ModelHydrologyProvider.class.getDeclaredMethod("publishColumn", plan, double.class);
        publish.setAccessible(true);
        int checks = 0;
        for (boolean fill : new boolean[] {false, true}) {
            for (boolean sea : new boolean[] {false, true}) {
                Object ordinary = ctor.newInstance(15, -17, 80.0, 75.1, 74.2, 5.8, 1.5,
                        fill, false, sea, Double.NaN);
                Object owned = ctor.newInstance(15, -17, 80.0, 75.1, 74.2, 5.8, 1.5,
                        fill, false, sea, 76.8);
                for (String method : new String[] {"connectorEndpoint", "integerLow", "integerHigh",
                        "surface", "carved", "fill", "seaBed"}) {
                    require(call(plan, method, ordinary).equals(call(plan, method, owned)), method);
                    checks++;
                }
                require(call(plan, "connectorEndpoint", owned).equals(fill && !sea), "original eligibility");
                HydrologyBlockCarvedColumn col = (HydrologyBlockCarvedColumn) publish.invoke(null, owned, 81.0);
                require(col.lakePlan() && col.fillWater(), "published lake authority");
                require(col.waterSurfaceY() == 76.8 && col.carvedGroundY() == 74.2
                        && col.erosion() == 5.8, "publication must not replace the river bed");
                checks += 3;
            }
        }
        System.out.println("PASS LakeConnectorOwnershipProbe checks=" + checks
                + " synthetic only; raw production comparison remains required");
    }

    private static Object call(Class<?> type, String name, Object target) throws Exception {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
