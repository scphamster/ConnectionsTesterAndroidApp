package com.harrysoft.androidbluetoothserial.demoapp

//import android.R
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.CommandHandler
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.Commands
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.PinNumT
import kotlinx.android.synthetic.main.actty_device_controll.*

class DeviceControlActivity : AppCompatActivity() {
    private val model by lazy {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                .create(DeviceControlViewModel::class.java)
    }
    private val numberOfFoundBoards by lazy { findViewById<TextView>(R.id.number_of_found_boards_vw) }

    private inner class CheckResultViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {
        private val layout: RelativeLayout by lazy { view.findViewById(R.id.single_check_result) }
        private val pinNumber: TextView by lazy { view.findViewById(R.id.pin_description) }
        private val foundConnections: TextView by lazy { view.findViewById(R.id.connections) }

        fun setup(pin_number: PinNumT, connected_to: Array<PinNumT>) {
            pinNumber.text = pin_number.toString()

            if (connected_to.isEmpty()) {
                foundConnections.text = "Not connected"
            }
            else {
                foundConnections.text = connected_to.joinToString(" ")
            }
        }
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<CheckResultViewHolder>() {
        private var numberOfPins = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckResultViewHolder {
            return CheckResultViewHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item, parent, false))
        }

        override fun getItemCount() = model.commandHandler.ioBoards.pinCount

        override fun onBindViewHolder(holder: CheckResultViewHolder, position: Int) {
            return holder.setup()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.actty_device_controll)

        if (!model.setupViewModel(intent.getStringExtra("name")!!, intent.getStringExtra("mac"))) {
            finish()
            return
        }

        setupAllListeners()

        Log.d(Tag, "device control created")
    }

    override fun onBackPressed() {
        finish()
    }

    private fun setupAllListeners() {
        model.commandHandler.numberOfConnectedBoards.observe(this) { boards_count: BoardCountT ->
            numberOfFoundBoards.text = numberOfFoundBoards.text.toString()
                    .format(boards_count)
        }

        model.commandHandler.connectionStatus.observe(this) { connection_status: CommandHandler.ConnectionStatus ->
            when (connection_status) {
                CommandHandler.ConnectionStatus.CONNECTED -> {
                    model.commandHandler.test1()
                }

                else -> {}
            }

        }

        findViewById<Button>(R.id.cmd1_button).setOnClickListener() {
            model.commandHandler.connect()
        }

        findViewById<Button>(R.id.cmd2_button).setOnClickListener() {
            model.commandHandler.sendCommand(Commands.CheckConnectivity())
            Log.d(Tag, "Command sent: ${getString(R.string.set_pin_cmd)}")
        }
    }

    companion object {
        private val Tag: String = "DeviceControl"
    }
}