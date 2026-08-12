package org.traccar.client

/**
 * The last activity the platform reported, so a position can say how the
 * device was moving when it was recorded.
 *
 * Written by [ActivityRecognitionDetector], read when a position is
 * processed. The detector only runs while stop detection is enabled, so this
 * stays empty when it is off - reporting nothing is correct there, since
 * nothing is being detected.
 */
class ActivityState {

    @Volatile
    var activity: String? = null
        private set

    @Volatile
    var confidence: Int? = null
        private set

    fun update(activity: String, confidence: Int?) {
        this.activity = activity
        this.confidence = confidence
    }
}
