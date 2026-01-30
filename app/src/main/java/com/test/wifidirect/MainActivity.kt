package com.test.wifidirect

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity(), WiFiDirectManager.Callback {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var wifiDirectManager: WiFiDirectManager

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnAutoConnect: Button
    private lateinit var btnDiscover: Button
    private lateinit var btnDisconnect: Button
    private lateinit var recyclerDevices: RecyclerView
    private lateinit var scrollLog: ScrollView

    private val deviceList = mutableListOf<WifiP2pDevice>()
    private lateinit var deviceAdapter: DeviceAdapter
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        wifiDirectManager = WiFiDirectManager(this)
        wifiDirectManager.callback = this

        btnAutoConnect.setOnClickListener {
            if (checkPermissions()) {
                wifiDirectManager.autoDiscoverAndConnect()
            }
        }

        btnDiscover.setOnClickListener {
            if (checkPermissions()) {
                wifiDirectManager.discoverPeers()
            }
        }

        btnDisconnect.setOnClickListener {
            wifiDirectManager.disconnect()
        }

        deviceAdapter = DeviceAdapter(deviceList) { device ->
            appendLog("手动选择连接: ${device.deviceName} [${device.deviceAddress}]")
            wifiDirectManager.connectToDevice(device)
        }
        recyclerDevices.layoutManager = LinearLayoutManager(this)
        recyclerDevices.adapter = deviceAdapter

        if (checkPermissions()) {
            wifiDirectManager.init()
        }
    }

    /**
     * 纯代码构建 UI，不依赖 XML 布局
     */
    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // 标题
        root.addView(TextView(this).apply {
            text = "WiFi Direct 一键连接测试"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        // 状态显示
        tvStatus = TextView(this).apply {
            text = "状态: 等待操作"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        root.addView(tvStatus)

        // 按钮行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 8
        }

        btnAutoConnect = Button(this).apply {
            text = "一键连接"
            layoutParams = btnParams
        }
        btnDiscover = Button(this).apply {
            text = "搜索设备"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
        }
        btnDisconnect = Button(this).apply {
            text = "断开连接"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(btnAutoConnect)
        btnRow.addView(btnDiscover)
        btnRow.addView(btnDisconnect)
        root.addView(btnRow)

        // 设备列表标题
        root.addView(TextView(this).apply {
            text = "发现的设备 (点击可手动连接):"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })

        // 设备列表
        recyclerDevices = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(recyclerDevices)

        // 日志标题
        root.addView(TextView(this).apply {
            text = "运行日志:"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        })

        // 日志滚动区域
        scrollLog = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(0xFFF0F0F0.toInt())
        }
        tvLog = TextView(this).apply {
            textSize = 12f
            setPadding(8, 8, 8, 8)
            text = "等待操作...\n"
        }
        scrollLog.addView(tvLog)
        root.addView(scrollLog)

        setContentView(root)
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            logBuilder.append("[$time] $msg\n")
            tvLog.text = logBuilder.toString()
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ========== WiFiDirectManager.Callback ==========

    override fun onStatusChanged(message: String) {
        runOnUiThread { tvStatus.text = "状态: $message" }
        appendLog(message)
    }

    override fun onDevicesFound(devices: List<WifiP2pDevice>) {
        runOnUiThread {
            deviceList.clear()
            deviceList.addAll(devices)
            deviceAdapter.notifyDataSetChanged()
        }
        appendLog("发现 ${devices.size} 个设备")
        devices.forEach { d ->
            appendLog("  - ${d.deviceName} [${d.deviceAddress}] 状态=${getDeviceStatus(d.status)}")
        }
    }

    override fun onConnected(info: WifiP2pInfo) {
        val role = if (info.isGroupOwner) "群组拥有者(GO)" else "客户端(Client)"
        appendLog("连接成功! 角色: $role, GO地址: ${info.groupOwnerAddress?.hostAddress}")
    }

    override fun onDisconnected() {
        appendLog("已断开 WiFi Direct 连接")
    }

    override fun onError(error: String) {
        appendLog("错误: $error")
    }

    // ========== 权限处理 ==========

    private fun checkPermissions(): Boolean {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                appendLog("权限已授予")
                wifiDirectManager.init()
            } else {
                appendLog("错误: 权限被拒绝，WiFi Direct 需要位置权限才能工作")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiDirectManager.destroy()
    }

    private fun getDeviceStatus(status: Int): String {
        return when (status) {
            WifiP2pDevice.AVAILABLE -> "可用"
            WifiP2pDevice.INVITED -> "已邀请"
            WifiP2pDevice.CONNECTED -> "已连接"
            WifiP2pDevice.FAILED -> "失败"
            WifiP2pDevice.UNAVAILABLE -> "不可用"
            else -> "未知"
        }
    }

    // ========== 设备列表适配器 ==========

    private class DeviceAdapter(
        private val devices: List<WifiP2pDevice>,
        private val onClick: (WifiP2pDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        class VH(val view: LinearLayout, val tvName: TextView, val tvInfo: TextView) :
            RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }
            val tvName = TextView(parent.context).apply {
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val tvInfo = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(0xFF666666.toInt())
            }
            layout.addView(tvName)
            layout.addView(tvInfo)
            return VH(layout, tvName, tvInfo)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val device = devices[position]
            holder.tvName.text = device.deviceName.ifEmpty { "未知设备" }
            holder.tvInfo.text = "${device.deviceAddress} | ${getStatus(device.status)}"
            holder.view.setOnClickListener { onClick(device) }
        }

        override fun getItemCount() = devices.size

        private fun getStatus(s: Int) = when (s) {
            WifiP2pDevice.AVAILABLE -> "可用"
            WifiP2pDevice.INVITED -> "已邀请"
            WifiP2pDevice.CONNECTED -> "已连接"
            WifiP2pDevice.FAILED -> "失败"
            WifiP2pDevice.UNAVAILABLE -> "不可用"
            else -> "未知"
        }
    }
}
