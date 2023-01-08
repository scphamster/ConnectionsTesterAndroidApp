import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.*
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.toConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

private typealias Header = ControllerResponseInterpreter.MessageHeader

class ControllerResponseInterpreterTest {
    @Test
    fun StringGetAllIntegers() {
        val test_string = "22:35(32.5)abcd12345 -12345"

        val result =
            test_string.getAllIntegers()

        assertTrue("", result.get(0) == 22)
        assertTrue("", result.get(1) == 35)
        assertTrue("", result.get(2) == 12345)
        assertTrue("", result.get(3) == -12345)
    }

    @Test
    fun StringGetAllFloats() {
        val test_string = "22:35(32.5)abcd12345 .345 234. 234.abc 0.1234 -1234.4 12.34.56"

        val result = test_string.getAllFloats()

        result.forEach{ number -> println(number)}

        assertTrue("", result.get(0) == 32.5.toFloat())
        assertTrue("", result.get(1) == 0.1234.toFloat())
        assertTrue("minus sign", result.get(2) == -1234.4.toFloat())
        assertTrue("found more than should", result.size == 3)
    }
}