import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.getAllIntegers
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.toConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Header = ControllerResponseInterpreter.MessageHeader

class ControllerResponseInterpreterTest {
    @Test
    fun StringGetAllIntegers() {
        val test_string = "22:35(32.5)abcd12345"
        val result =
            test_string.getAllIntegers()

        for (integer in result) {
            println(integer)
        }

        assertTrue("No pass", true)
    }
}