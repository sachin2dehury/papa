package tart

import tart.AppState.Value.NumberValue
import tart.AppState.Value.SerializedAsync
import tart.AppState.Value.StringValue

sealed class AppState {

  sealed class Value : AppState() {
    class StringValue(val string: String) : Value()
    class NumberValue(val number: Number) : Value()

    class SerializedAsync(val value: Any) : Value()
    object NoValue : Value()
  }

  class ValueOnFrameRendered(val onFrameRendered: () -> Value) : AppState()

  companion object {
    fun value(string: String) = StringValue(string)
    fun value(number: Number) = NumberValue(number)

    /**
     * Serialized to a format appropriate for analytics, typically json.
     */
    fun serializedAsync(serializedAsync: Any) = SerializedAsync(serializedAsync)

    /**
     * Delay retrieving the value until the frame has been rendered. This allows providing
     * app state such as the number of recycler view rows rendered.
     */
    fun valueOnFrameRendered(onFrameRendered: () -> Value) = ValueOnFrameRendered(onFrameRendered)
  }
}
