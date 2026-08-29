package com.vboard.app.llm;

/**
 * The refiner as seen from the keyboard process. Everything here is blocking and
 * every call is allowed to fail: the model lives in another process now, and the
 * whole point of that is that its death is survivable.
 */
interface ILlmRefiner {

    /** Loads the model so the first real call does not pay init. */
    boolean preload();

    /** Dictation refinement; null when it fails, times out, or looks wrong. */
    String refine(String text, long timeoutMs);

    /**
     * "AI fix" correction. Returns a Bundle with "text" (String, present on
     * success) or "failure" (String, a SmartFailure name).
     */
    Bundle correct(String text, long timeoutMs);
}
