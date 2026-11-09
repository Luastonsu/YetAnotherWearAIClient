package com.yawac.client.ui;

/** Critically damped motion; changing direction keeps both position and velocity. */
final class MorphSpring {
    double position, velocity, target;
    private static final double FREQUENCY = 18;

    void target(boolean joined) { target = joined ? 1 : 0; }
    void snap() { position = target; velocity = 0; }
    boolean moving() { return Math.abs(position - target) > .0008 || Math.abs(velocity) > .012; }

    boolean advance(double seconds) {
        // A delayed frame must not consume the entire transition while the IME is closing.
        double dt = Math.max(0, Math.min(.04, seconds));
        double distance = position - target;
        double impulse = velocity + FREQUENCY * distance;
        double decay = Math.exp(-FREQUENCY * dt);
        position = target + (distance + impulse * dt) * decay;
        velocity = (velocity - FREQUENCY * impulse * dt) * decay;
        if (!moving()) { snap(); return false; }
        return true;
    }
}
