package com.middleware.panorama.wp5;

/**
 * Fake vehicle for development: sweeps the steering wheel left/right in reverse gear
 * and moves obstacles closer/further so every zone colour appears.
 * Enable with -Dwp5.simulate=true. NEVER enable in a real vehicle.
 */
final class Wp5Simulator {
    private final Wp5Service svc;
    Wp5Simulator(Wp5Service svc) { this.svc = svc; }

    // FIXED values (curb does NOT move). Change with:  -Dwp5.sim.curbM=0.4  -Dwp5.sim.steerDeg=120
    // Old moving demo:  -Dwp5.sim.sweep=true
    private static final boolean SWEEP = Boolean.getBoolean("wp5.sim.sweep");
    private static final double FIXED_CURB_M = Double.parseDouble(System.getProperty("wp5.sim.curbM", "0.70"));
    private static final double FIXED_STEER_DEG = Double.parseDouble(System.getProperty("wp5.sim.steerDeg", "0"));

    void start() {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                double sec = (System.currentTimeMillis() - t0) / 1000.0;
                double steer = SWEEP ? 450.0 * Math.sin(2 * Math.PI * sec / 12.0) : FIXED_STEER_DEG;   // fixed unless sweep
                svc.onSteeringInput(steer, "R", 1.5);

                double rear = SWEEP ? 1.35 + 1.15 * Math.sin(2 * Math.PI * sec / 8.0) : 1.35;          // obstacle (ignored by curb-only zones)
                double curb = SWEEP ? 0.70 + 0.45 * Math.sin(2 * Math.PI * sec / 6.0 + 1) : FIXED_CURB_M;   // curb distance: FIXED
                double right = 2.5;                                                  // clear
                svc.onRangeJson("{\"sensors\":["
                    + s("RC", "REAR_CENTER", rear, "OBSTACLE", "ULTRASONIC", Double.NaN) + ","
                    + s("LR", "LEFT_REAR", curb, "CURB", "ULTRASONIC", 0.12) + ","
                    + s("RR", "RIGHT_REAR", right, "CURB", "ULTRASONIC", Double.NaN) + "]}");
                try { Thread.sleep(50); } catch (InterruptedException e) { return; } // 20 Hz
            }
        }, "wp5-simulator");
        t.setDaemon(true);
        t.start();
    }

    private static String s(String id, String pos, double d, String kind, String src, double h) {
        return "{\"id\":\"" + id + "\",\"pos\":\"" + pos + "\",\"distanceM\":" + Json.num(d)
            + ",\"kind\":\"" + kind + "\",\"source\":\"" + src + "\""
            + (Double.isNaN(h) ? "" : ",\"heightM\":" + Json.num(h)) + "}";
    }
}
