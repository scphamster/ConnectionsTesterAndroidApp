import com.github.scphamster.bluetoothConnectionsTester.circuit.Resistance
import com.github.scphamster.bluetoothConnectionsTester.circuit.Voltage
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsTest {
    @Test
    fun testResistanceClass() {
        val sign = "\u03A9"

        val resistance = Resistance(1120.0f, 2).toString()
        val resistance2 = Resistance(2e-9f, 3).toString()
        assertTrue("$resistance != 1.12k$sign", resistance == "1.12k$sign")
        assertTrue("$resistance2 != 2.000n$sign", resistance2 == "2.000n$sign")
    }

    @Test
    fun testVoltageClass() {
        val voltage = Voltage(1234.5f,3).toString()
        val voltage2 = Voltage(0.01f,3).toString()
        val voltage3 = Voltage(0.002f,3).toString()

        assertTrue("$voltage != 1.235kV", voltage == "1.235kV")
        assertTrue("$voltage2 != 10.000mV", voltage2 == "10.000mV")
        assertTrue("$voltage3 != 2.000mV", voltage3 == "2.000mV")
    }
}