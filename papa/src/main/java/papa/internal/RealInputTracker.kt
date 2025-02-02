package papa.internal

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import com.squareup.papa.R
import curtains.Curtains
import curtains.KeyEventInterceptor
import curtains.OnRootViewAddedListener
import curtains.TouchEventInterceptor
import curtains.keyEventInterceptors
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import papa.DeliveredInput
import papa.InputTracker
import papa.SafeTrace
import papa.internal.FrozenFrameOnTouchDetector.findPressedView
import papa.safeTrace
import kotlin.time.Duration.Companion.nanoseconds

internal object RealInputTracker : InputTracker {

  override val motionEventTriggeringClick: DeliveredInput<MotionEvent>?
    get() = motionEventTriggeringClickLocal.get()?.input

  override val currentKeyEvent: DeliveredInput<KeyEvent>?
    get() = currentKeyEventLocal.get()

  /**
   * The thread locals here aren't in charge of actually holding the event holder for the duration
   * of its lifecycle. Instead, they are exposing the event during specific time spans (sandwiching
   * onClick callback invocations) so that we can reach back here from an onClick and capture the
   * event. The sandwiching also also ensures that if we reach back outside of an onClick sandwich
   * we actually find nothing even if overall the EventHolder is still hanging out in memory.
   */
  private val motionEventTriggeringClickLocal = ThreadLocal<MotionEventHolder>()
  private val currentKeyEventLocal = ThreadLocal<DeliveredInput<KeyEvent>>()

  private val handler = Handler(Looper.getMainLooper())

  class MotionEventHolder(var input: DeliveredInput<MotionEvent>) : Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()

    override fun doFrame(frameTimeNanos: Long) {
      // We increase the counter right after the frame callback. This means we don't count a frame
      // if this event is consumed as part of the frame we did the increment in.
      // There's a slight edge case: if the event consumption triggered in between doFrame and
      // the post at front of queue, the count would be short by 1. We can live with this, it's
      // unlikely to happen unless an event is triggered from a postAtFront.
      mainHandler.postAtFrontOfQueueAsync {
        input = input.increaseFrameCount()
      }
      choreographer.postFrameCallback(this)
    }

    fun startCounting() {
      choreographer.postFrameCallback(this)
    }

    fun stopCounting() {
      choreographer.removeFrameCallback(this)
    }
  }

  private val listener = OnRootViewAddedListener { view ->
    view.phoneWindow?.let { window ->
      if (view.windowAttachCount == 0) {
        window.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
          // Note: what if we get 2 taps in a single dispatch loop? Then we're simply posting the
          // following: (recordTouch, onClick, clearTouch, recordTouch, onClick, clearTouch).
          val deliveryUptime = System.nanoTime().nanoseconds
          val isActionUp = motionEvent.action == MotionEvent.ACTION_UP

          //  We wrap the event in a holder so that we can actually replace the event within the
          //  holder. Why replace it? Because we want to increase the frame count over time, but we
          //  want to do that by swapping an immutable event, so that if we capture such event at
          //  time N and then the count gets updated at N + 1, the count update isn't reflected in
          //  the code that captured the event at time N.
          val actionUpEventHolder = if (isActionUp) {
            val cookie = deliveryUptime.inWholeMilliseconds.rem(Int.MAX_VALUE).toInt()
            SafeTrace.beginAsyncSection(TAP_INTERACTION_SECTION, cookie)
            MotionEventHolder(
              DeliveredInput(
                MotionEvent.obtain(motionEvent),
                deliveryUptime,
                0
              ) {
                SafeTrace.endAsyncSection(TAP_INTERACTION_SECTION, cookie)
              }).also {
              it.startCounting()
            }
          } else {
            null
          }

          val setEventForPostedClick = Runnable {
            motionEventTriggeringClickLocal.set(actionUpEventHolder)
          }

          if (actionUpEventHolder != null) {
            handler.post(setEventForPostedClick)
          }

          val dispatchState = safeTrace({ MotionEvent.actionToString(motionEvent.action) }) {
            // Storing in case the action up is immediately triggering a click.
            motionEventTriggeringClickLocal.set(actionUpEventHolder)
            try {
              dispatch(motionEvent)
            } finally {
              motionEventTriggeringClickLocal.set(null)
            }
          }

          // Android posts onClick callbacks when it receives the up event. So here we leverage
          // afterTouchEvent at which point the onClick has been posted, and by posting then we ensure
          // we're clearing the event right after the onclick is handled.
          if (isActionUp) {
            val clearEventForPostedClick = Runnable {
              actionUpEventHolder!!.stopCounting()
              val actionUpEvent = actionUpEventHolder.input
              actionUpEvent.event.recycle()
              actionUpEvent.takeOverTraceEnd()?.invoke()
              if (motionEventTriggeringClickLocal.get() === actionUpEventHolder) {
                motionEventTriggeringClickLocal.set(null)
              }
            }

            val dispatchEnd = SystemClock.uptimeMillis()
            val viewPressedAfterDispatch = safeTrace("findPressedView()") {
              (window.decorView as? ViewGroup)?.findPressedView()
            }
            // AbsListView subclasses post clicks with a delay.
            // https://issuetracker.google.com/issues/232962097
            // Note: If a listview has no long press item listener, then long press are delivered
            // as a click on UP. In that case the delivery is immediate (no delay) and the post
            // dispatch state is not pressed (so we run into the else case here, which is good)
            if (viewPressedAfterDispatch is AbsListView) {
              val listViewTapDelayMillis = ViewConfiguration.getPressedStateDuration()
              val setEventTime =
                (deliveryUptime.inWholeMilliseconds + listViewTapDelayMillis) - 1
              val clearEventTime = dispatchEnd + listViewTapDelayMillis
              handler.removeCallbacks(setEventForPostedClick)
              handler.postAtTime(setEventForPostedClick, setEventTime)
              handler.postAtTime(clearEventForPostedClick, clearEventTime)
            } else {
              handler.post(clearEventForPostedClick)
            }
          }
          dispatchState
        }
        window.keyEventInterceptors += KeyEventInterceptor { keyEvent, dispatch ->
          val traceSectionName = keyEvent.traceSectionName
          val now = System.nanoTime()
          val cookie = now.rem(Int.MAX_VALUE).toInt()
          SafeTrace.beginAsyncSection(traceSectionName, cookie)
          val input = DeliveredInput(keyEvent, now.nanoseconds, 0) {
            SafeTrace.endAsyncSection(traceSectionName, cookie)
          }
          currentKeyEventLocal.set(input)
          try {
            dispatch(keyEvent)
          } finally {
            currentKeyEventLocal.set(null)
            input.takeOverTraceEnd()?.invoke()
          }
        }
      }
    }
  }

  internal fun install(application: Application) {
    if (application.resources.getBoolean(R.bool.papa_track_input_events)) {
      Curtains.onRootViewsChangedListeners += listener
    }
  }

  private const val INTERACTION_SUFFIX = "Interaction"
  private const val TAP_INTERACTION_SECTION = "Tap $INTERACTION_SUFFIX"

  val KeyEvent.name: String
    get() = "${keyActionToString()} ${KeyEvent.keyCodeToString(keyCode)}"

  private val KeyEvent.traceSectionName: String
    get() = "$name $INTERACTION_SUFFIX"

  private fun KeyEvent.keyActionToString(): String {
    @Suppress("DEPRECATION")
    return when (action) {
      KeyEvent.ACTION_DOWN -> "ACTION_DOWN"
      KeyEvent.ACTION_UP -> "ACTION_UP"
      KeyEvent.ACTION_MULTIPLE -> "ACTION_MULTIPLE"
      else -> action.toString()
    }
  }
}
