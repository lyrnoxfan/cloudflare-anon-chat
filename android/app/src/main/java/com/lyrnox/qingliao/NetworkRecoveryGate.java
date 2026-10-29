package com.lyrnox.qingliao;

/** Coalesces connectivity callbacks into one rejoin after validated internet returns. */
final class NetworkRecoveryGate {
  private boolean connected;
  private boolean recoveryPending;

  void reset(boolean initiallyConnected) {
    connected = initiallyConnected;
    // If the Activity starts offline, let the first validated network trigger a fresh join.
    recoveryPending = !initiallyConnected;
  }

  /** Returns true exactly once when connectivity returns after a loss. */
  boolean observe(boolean nowConnected) {
    if (!nowConnected) {
      if (connected) recoveryPending = true;
      connected = false;
      return false;
    }
    boolean recovered = recoveryPending;
    recoveryPending = false;
    connected = true;
    return recovered;
  }
}
