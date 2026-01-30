package com.test.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * WiFi Direct 核心管理类
 * 负责发现设备、自动连接、状态回调
 */
class WiFiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WiFiDirectManager"
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val discoveredDevices = mutableListOf<WifiP2pDevice>()
    var callback: Callback? = null

    interface Callback {
        fun onStatusChanged(message: String)
        fun onDevicesFound(devices: List<WifiP2pDevice>)
        fun onConnected(info: WifiP2pInfo)
        fun onDisconnected()
        fun onError(error: String)
    }

    fun init() {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            callback?.onError("设备不支持 WiFi Direct")
            return
        }
        channel = manager!!.initialize(context, Looper.getMainLooper(), null)
        registerReceiver()
        callback?.onStatusChanged("WiFi Direct 已初始化")
    }

    private fun registerReceiver() {
        receiver = WiFiDirectReceiver()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /**
     * 开始发现附近 WiFi Direct 设备
     */
    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        callback?.onStatusChanged("正在搜索附近设备...")
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "开始发现设备")
                callback?.onStatusChanged("正在搜索设备中...")
            }

            override fun onFailure(reason: Int) {
                val msg = getFailureReason(reason)
                Log.e(TAG, "发现设备失败: $msg")
                callback?.onError("搜索失败: $msg")
            }
        })
    }

    /**
     * 停止发现设备
     */
    fun stopDiscovery() {
        manager?.stopPeerDiscovery(channel, null)
        callback?.onStatusChanged("已停止搜索")
    }

    /**
     * 一键连接指定设备 —— 核心功能
     * 程序化自动发起连接，无需用户在系统设置中手动点击
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice) {
        callback?.onStatusChanged("正在连接: ${device.deviceName}...")

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // 设置WPS方式为PBC(按钮方式)，自动配对无需用户输入PIN
            wps.setup = android.net.wifi.WpsInfo.PBC
        }

        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "连接请求已发送: ${device.deviceName}")
                callback?.onStatusChanged("连接请求已发送至: ${device.deviceName}")
            }

            override fun onFailure(reason: Int) {
                val msg = getFailureReason(reason)
                Log.e(TAG, "连接失败: $msg")
                callback?.onError("连接失败: $msg")
            }
        })
    }

    /**
     * 断开当前连接
     */
    fun disconnect() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                callback?.onStatusChanged("已断开连接")
                callback?.onDisconnected()
            }

            override fun onFailure(reason: Int) {
                callback?.onError("断开失败: ${getFailureReason(reason)}")
            }
        })
    }

    /**
     * 一键自动连接 —— 发现后自动连接第一个找到的设备
     */
    @SuppressLint("MissingPermission")
    fun autoDiscoverAndConnect() {
        callback?.onStatusChanged("一键连接：正在搜索设备...")
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                callback?.onStatusChanged("一键连接：搜索中，发现设备后将自动连接...")
                autoConnectPending = true
            }

            override fun onFailure(reason: Int) {
                callback?.onError("一键连接搜索失败: ${getFailureReason(reason)}")
                autoConnectPending = false
            }
        })
    }

    /** 标记是否正在等待自动连接 */
    @Volatile
    private var autoConnectPending = false

    fun destroy() {
        autoConnectPending = false
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        channel?.close()
    }

    private fun getFailureReason(reason: Int): String {
        return when (reason) {
            WifiP2pManager.ERROR -> "内部错误"
            WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持P2P"
            WifiP2pManager.BUSY -> "系统繁忙"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "无服务请求"
            else -> "未知错误($reason)"
        }
    }

    /**
     * 广播接收器 - 处理 WiFi Direct 系统事件
     */
    private inner class WiFiDirectReceiver : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        callback?.onStatusChanged("WiFi Direct 已启用")
                    } else {
                        callback?.onError("WiFi Direct 未启用，请开启WiFi")
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager?.requestPeers(channel) { peerList ->
                        val peers = peerList.deviceList.toList()
                        discoveredDevices.clear()
                        discoveredDevices.addAll(peers)
                        callback?.onDevicesFound(peers)

                        if (peers.isNotEmpty()) {
                            callback?.onStatusChanged("发现 ${peers.size} 个设备")
                        }

                        // 自动连接逻辑：发现设备后自动连接第一个
                        if (autoConnectPending && peers.isNotEmpty()) {
                            autoConnectPending = false
                            val target = peers[0]
                            callback?.onStatusChanged("一键连接：自动连接 ${target.deviceName}...")
                            connectToDevice(target)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            android.net.NetworkInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        manager?.requestConnectionInfo(channel) { info ->
                            if (info != null) {
                                callback?.onConnected(info)
                                val role = if (info.isGroupOwner) "群组拥有者" else "客户端"
                                callback?.onStatusChanged(
                                    "已连接! 角色: $role\n群组拥有者IP: ${info.groupOwnerAddress?.hostAddress}"
                                )
                            }
                        }
                    } else {
                        callback?.onDisconnected()
                        callback?.onStatusChanged("连接已断开")
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            WifiP2pDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    if (device != null) {
                        Log.d(TAG, "本机设备: ${device.deviceName} [${device.deviceAddress}]")
                    }
                }
            }
        }
    }
}
