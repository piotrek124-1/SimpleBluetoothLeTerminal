package de.kai_morich.simple_bluetooth_le_terminal

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import de.kai_morich.simple_bluetooth_le_terminal.SerialService.SerialBinder
import de.kai_morich.simple_bluetooth_le_terminal.TextUtil.HexWatcher
import de.kai_morich.simple_bluetooth_le_terminal.TextUtil.toCaretString
import de.kai_morich.simple_bluetooth_le_terminal.TextUtil.toHexString
import java.util.*

class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    private var deviceAddress: String? = null
    private var service: SerialService? = null
    private var receiveText: TextView? = null
    private var sendText: TextView? = null
    private var hexWatcher: HexWatcher? = null
    private var connected = Connected.False
    private var initialStart = true
    private var hexEnabled = false
    private var pendingNewline = false
    private var newline: String? = TextUtil.newline_crlf

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        deviceAddress = arguments!!.getString("device")
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        activity!!.stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) service!!.attach(this) else activity!!.startService(
            Intent(
                activity,
                SerialService::class.java
            )
        ) // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    override fun onStop() {
        if (service != null && !activity!!.isChangingConfigurations) service!!.detach()
        super.onStop()
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        getActivity()!!.bindService(
            Intent(getActivity(), SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDetach() {
        try {
            activity!!.unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        if (initialStart && service != null) {
            initialStart = false
            activity!!.runOnUiThread { connect() }
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialBinder).service
        service!!.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            activity!!.runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText =
            view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
//        receiveText.setTextColor(resources.getColor(R.color.colorRecieveText)) // set as default color to reduce number of spans
        receiveText?.setMovementMethod(ScrollingMovementMethod.getInstance())
        sendText = view.findViewById(R.id.send_text)
        hexWatcher = HexWatcher(sendText)
        hexWatcher!!.enable(hexEnabled)
        sendText?.addTextChangedListener(hexWatcher)
        sendText?.setHint(if (hexEnabled) "HEX mode" else "")
        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { v: View? -> send(sendText?.getText().toString()) }
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
        menu.findItem(R.id.hex).isChecked = hexEnabled
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        return if (id == R.id.clear) {
            receiveText!!.text = ""
            true
        } else if (id == R.id.newline) {
            val newlineNames = resources.getStringArray(R.array.newline_names)
            val newlineValues = resources.getStringArray(R.array.newline_values)
            val pos = Arrays.asList(*newlineValues).indexOf(newline)
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Newline")
            builder.setSingleChoiceItems(newlineNames, pos) { dialog: DialogInterface, item1: Int ->
                newline = newlineValues[item1]
                dialog.dismiss()
            }
            builder.create().show()
            true
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled
            sendText!!.text = ""
            hexWatcher!!.enable(hexEnabled)
            sendText!!.hint = if (hexEnabled) "HEX mode" else ""
            item.isChecked = hexEnabled
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    /*
     * Serial + UI
     */
    private fun connect() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            status("connecting...")
            connected = Connected.Pending
            val socket = SerialSocket(activity!!.applicationContext, device)
            service!!.connect(socket)
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        service!!.disconnect()
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray?
            if (hexEnabled) {
                val sb = StringBuilder()
                toHexString(sb, TextUtil.fromHexString(str))
                toHexString(sb, newline!!.toByteArray())
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = (str + newline).toByteArray()
            }
            val spn = SpannableStringBuilder(
                """
    $msg
    
    """.trimIndent()
            )
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText!!.append(spn)
            service!!.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(data: ByteArray?) {
        if (hexEnabled) {
            receiveText!!.append(toHexString(data!!) + '\n')
        } else {
            var msg = String(data!!)
            if (newline == TextUtil.newline_crlf && msg.length > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg[0] == '\n') {
                    val edt = receiveText!!.editableText
                    if (edt != null && edt.length > 1) edt.replace(edt.length - 2, edt.length, "")
                }
                pendingNewline = msg[msg.length - 1] == '\r'
            }
            receiveText!!.append(toCaretString(msg, newline!!.length != 0))
        }
    }

    private fun status(str: String) {
        val spn = SpannableStringBuilder(
            """
    $str
    
    """.trimIndent()
        )
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText!!.append(spn)
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
    }

    override fun onSerialConnectError(e: Exception?) {
        status("connection failed: " + e!!.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray?) {
        receive(data)
    }

    override fun onSerialIoError(e: Exception?) {
        status("connection lost: " + e!!.message)
        disconnect()
    }
}