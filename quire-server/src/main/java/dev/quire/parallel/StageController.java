package dev.quire.parallel;

/**
 * Chooses, per world and tick stage, how to run it by measuring: arm 0 is serial, arm {@code 1 + s} is
 * tile-parallel with tile shift {@code s}. Every arm is measured in bursts of {@link #BURST} consecutive
 * ticks, of which only the last {@link #RECORDED} are recorded (an arm that has not run for a while starts
 * cold: parked workers, cold caches). After each arm had one burst, the arm with the lowest moving average of
 * nanoseconds per unit is used, and every {@link #PROBE_INTERVAL} ticks another arm (round robin) gets a burst
 * so the choice follows the workload.
 */
public final class StageController {
    public static final int SERIAL = 0;
    private static final int BURST = 8;
    private static final int RECORDED = 5;
    private static final int PROBE_INTERVAL = 300;
    private static final double ALPHA = 0.3;

    private final double[] cost;
    private final long[] runs;
    private long ticks;
    private int burstLeft;
    private int burstArm;
    private int nextProbe = 1;
    private boolean recordThisTick;

    public StageController(final int arms) {
        this.cost = new double[arms];
        this.runs = new long[arms];
        java.util.Arrays.fill(this.cost, Double.NaN);
    }

    public int arms() {
        return this.cost.length;
    }

    private int best() {
        int best = SERIAL;
        for (int i = 1; i < this.cost.length; i++) {
            if (this.cost[i] < this.cost[best]) {
                best = i;
            }
        }
        return best;
    }

    public int choose() {
        this.ticks++;
        if (this.burstLeft == 0) {
            for (int i = 0; i < this.cost.length; i++) {
                if (Double.isNaN(this.cost[i])) {
                    this.burstLeft = BURST;
                    this.burstArm = i;
                    break;
                }
            }
            if (this.burstLeft == 0 && this.ticks % PROBE_INTERVAL == 0) {
                final int best = this.best();
                int probe = this.nextProbe % this.cost.length;
                if (probe == best) {
                    probe = (probe + 1) % this.cost.length;
                }
                this.nextProbe = probe + 1;
                this.burstLeft = BURST;
                this.burstArm = probe;
            }
        }
        final int arm;
        if (this.burstLeft > 0) {
            this.burstLeft--;
            arm = this.burstArm;
            this.recordThisTick = this.burstLeft < RECORDED;
        } else {
            arm = this.best();
            this.recordThisTick = true;
        }
        return arm;
    }

    public void record(final int arm, final long nanos, final int units) {
        if (units <= 0) {
            return;
        }
        this.runs[arm]++;
        if (!this.recordThisTick) {
            return;
        }
        final double perUnit = nanos / (double) units;
        this.cost[arm] = Double.isNaN(this.cost[arm]) ? perUnit : this.cost[arm] * (1 - ALPHA) + perUnit * ALPHA;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.cost.length; i++) {
            sb.append(i == SERIAL ? "serial" : "t" + (i - 1)).append('=').append(String.format("%.0f", this.cost[i]))
                .append("ns/").append(this.runs[i]).append(' ');
        }
        return sb.toString().trim();
    }
}
