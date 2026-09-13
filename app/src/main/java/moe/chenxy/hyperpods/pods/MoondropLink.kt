/*
 * HyperPods for Moondrop — 协议客户端
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 与水月雨耳机建立一条控制通道并维护状态。两条传输：
 *   · GAIA v3 over BLE GATT  —— 大多数机型（服务 00001100-d102-…）
 *   · GAIA v4 over RFCOMM/SPP —— 布丁 PUDDING 等（UUID 00001101-0000-1000-8000-00805f9b34fb）
 *
 * 设计要点（吸取参考实现的经验）：
 *   1. **请求/响应关联**：GAIA 没有序列号，按 (feature, command) 建 pending 表，
 *      带超时。参考项目（OppoPods）没有这层，只靠 delay 排序，容易出现状态错乱。
 *   2. **单写者 Mutex**：所有写操作串行化，避免并发交叠。
 *   3. **能力优先**：连接后先探测 GET_SUPPORTED_FEATURES 位图，用真实能力决定 UI 展示，
 *      型号档案只提供差异点（映射表等）。
 *   4. **电量修复**：先 cmd0 问出设备支持的电池类型，再按该集合查询；解析按 type 归位；
 *      type 0（单设备）同时充填左右耳。见 BatteryCodec 的根因说明。
 */
package moe.chenxy.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.chenxy.hyperpods.core.AncMode
import moe.chenxy.hyperpods.core.AncPathKind
import moe.chenxy.hyperpods.core.BATTERY_UNKNOWN
import moe.chenxy.hyperpods.core.BatteryCodec
import moe.chenxy.hyperpods.core.BatteryState
import moe.chenxy.hyperpods.core.Gaia
import moe.chenxy.hyperpods.core.GaiaFramer
import moe.chenxy.hyperpods.core.MoondropModel
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.core.PodTransport
import java.util.UUID

private const val TAG = "MoondropLink"

interface PodListener {
    fun onEvent(event: PodEvent)
}

object MoondropLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    @Volatile private var appContext: Context? = null
    @Volatile private var listener: PodListener? = null

    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var model: MoondropModel = MoondropModels.FALLBACK
    @Volatile private var useRfcomm = false

    // BLE
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null

    // SPP
    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null
    private var pollJob: Job? = null

    private val framer = GaiaFramer()
    private val batteryState = BatteryState()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 状态 ────────────────────────────────────────────────────────────────
    @Volatile private var connected = false
    @Volatile private var ancIndex = -1
    @Volatile private var ancModes: List<AncMode> = emptyList()
    @Volatile private var gainIndex = -1
    @Volatile private var ledOn: Boolean? = null
    @Volatile private var promptToneOn: Boolean? = null
    @Volatile private var promptVolumeRaw = -1
    @Volatile private var lhdcOn: Boolean? = null
    @Volatile private var activeCodec = ""
    @Volatile private var dualConnectionOn: Boolean? = null
    @Volatile private var lowLatencyOn: Boolean? = null
    @Volatile private var capabilities = PodCapabilities()

    // 每个 feature 只保留一个等待者，避免并发请求互相覆盖（GAIA 无序列号）
    private val responses = HashMap<Int, java.util.concurrent.CompletableFuture<ByteArray>>()

    private val ANC_TIMEOUT_MS = 1500L
    private val CMD_TIMEOUT_MS = 2000L

    fun init(context: Context, listener: PodListener) {
        appContext = context.applicationContext
        this.listener = listener
    }

    fun sniffModel(deviceName: String?): MoondropModel? = MoondropModels.match(deviceName)

    val currentModel: MoondropModel get() = model

    fun snapshot(): PodSnapshot = PodSnapshot(
        deviceName = device?.name ?: "",
        deviceAddress = device?.address ?: "",
        modelId = model.id,
        modelName = model.nameZh,
        modelVerified = model.verified,
        connected = connected,
        transport = if (useRfcomm) "GAIA v4 / RFCOMM" else "GAIA v3 / BLE",
        battery = BatterySnapshot(
            left = batteryState.currentLeft.level,
            right = batteryState.currentRight.level,
            case = batteryState.currentCase.level,
            leftCharging = batteryState.currentLeft.charging,
            rightCharging = batteryState.currentRight.charging,
            caseCharging = batteryState.currentCase.charging,
            singleDevice = batteryState.usesSingleDeviceBattery(),
        ),
        ancIndex = ancIndex,
        ancModes = ancModes,
        gainIndex = gainIndex,
        gainLabels = model.dc.gainLabels,
        ledOn = ledOn,
        promptToneOn = promptToneOn,
        promptVolumeRaw = promptVolumeRaw,
        promptVolumeMax = model.features.promptVolumeMax,
        lhdcOn = lhdcOn,
        activeCodec = activeCodec,
        dualConnectionOn = dualConnectionOn,
        lowLatencyOn = lowLatencyOn,
        capabilities = capabilities,
    )

    private fun emit(e: PodEvent) {
        mainHandler.post { listener?.onEvent(e) }
    }

    private fun emitState() {
        mainHandler.post { listener?.onEvent(PodEvent.Connected(snapshot())) }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 连接
    // ══════════════════════════════════════════════════════════════════════

    fun connect(btDevice: BluetoothDevice, preferRfcomm: Boolean = false) {
        if (connected && device?.address == btDevice.address) return
        disconnectInternal(notify = false)
        device = btDevice
        model = MoondropModels.match(btDevice.name) ?: MoondropModels.FALLBACK
        useRfcomm = preferRfcomm || model.transports.firstOrNull() == PodTransport.RFCOMM_GAIA
        framer.reset()
        batteryState.reset()
        capabilities = PodCapabilities()
        scope.launch {
            if (useRfcomm) connectRfcomm(btDevice) else connectGattInternal(btDevice)
        }
    }

    fun disconnect() = disconnectInternal(notify = true)

    private fun disconnectInternal(notify: Boolean) {
        connected = false
        pollJob?.cancel(); pollJob = null
        readerJob?.cancel(); readerJob = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}
        gatt = null; cmdChar = null
        device = null
        responses.clear()
        if (notify) emit(PodEvent.Disconnected)
    }

    // ── BLE GATT ───────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected status=$status")
                if (connected) disconnectInternal(notify = true)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "discover failed $status"); return }
            val svc = g.getService(UUID.fromString(Gaia.GATT_SERVICE)) ?: run {
                Log.w(TAG, "GAIA service not found; falling back to RFCOMM")
                // 双模设备：BLE 上没有 GAIA 服务时回退 SPP
                val d = device
                if (d != null) { useRfcomm = true; scope.launch { connectRfcomm(d) } }
                return
            }
            cmdChar = svc.getCharacteristic(UUID.fromString(Gaia.GATT_COMMAND))
            enableNotify(g, svc.getCharacteristic(UUID.fromString(Gaia.GATT_RESPONSE)))
            enableNotify(g, svc.getCharacteristic(UUID.fromString(Gaia.GATT_DATA)))
            if (cmdChar == null) { Log.w(TAG, "command characteristic missing"); return }
            connected = true
            Log.i(TAG, "GAIA GATT ready: ${device?.name} model=${model.nameZh}")
            scope.launch { afterConnected() }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onBytes(value)
        }

        @Deprecated("kept for API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { onBytes(it) }
        }
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic?) {
        ch ?: return
        try {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID.fromString(Gaia.CCCD)) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enableNotify failed", t)
        }
    }

    private fun connectGattInternal(btDevice: BluetoothDevice) {
        val ctx = appContext ?: return
        try {
            gatt = if (Build.VERSION.SDK_INT >= 26) {
                btDevice.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                btDevice.connectGatt(ctx, false, gattCallback)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "connectGatt failed", t)
        }
    }

    // ── RFCOMM / SPP ───────────────────────────────────────────────────────

    private fun connectRfcomm(btDevice: BluetoothDevice) {
        try {
            val uuid = UUID.fromString(Gaia.SPP_UUID)
            val s = try {
                btDevice.createRfcommSocketToServiceRecord(uuid)
            } catch (t: Throwable) {
                Log.w(TAG, "createRfcommSocketToServiceRecord failed, trying channel 1", t)
                @Suppress("DiscouragedPrivateApi")
                btDevice.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(btDevice, 1) as BluetoothSocket
            }
            s.connect()
            socket = s
            connected = true
            Log.i(TAG, "GAIA SPP ready: ${btDevice.name} model=${model.nameZh}")
            startReader(s)
            scope.launch { afterConnected() }
        } catch (t: Throwable) {
            Log.e(TAG, "RFCOMM connect failed", t)
            emit(PodEvent.Frame("ERR", "", "RFCOMM 连接失败: ${t.message}"))
        }
    }

    private fun startReader(s: BluetoothSocket) {
        readerJob = scope.launch {
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (connected && s.isConnected) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val burstEnd = try { input.available() == 0 } catch (_: Throwable) { true }
                    val chunk = buf.copyOf(n)
                    for (pdu in framer.feed(chunk, burstEnd)) onPdu(pdu)
                }
            } catch (t: Throwable) {
                if (connected) Log.w(TAG, "SPP reader stopped: ${t.message}")
            }
            if (connected) disconnectInternal(notify = true)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 写 / 收
    // ══════════════════════════════════════════════════════════════════════

    private fun write(pkt: ByteArray) {
        scope.launch {
            writeLock.withLock {
                try {
                    if (useRfcomm) {
                        socket?.outputStream?.apply { write(pkt); flush() }
                    } else {
                        val g = gatt ?: return@withLock
                        val c = cmdChar ?: return@withLock
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeCharacteristic(c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            c.value = pkt
                            @Suppress("DEPRECATION")
                            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(c)
                        }
                    }
                    emit(PodEvent.Frame("TX", Gaia.hex(pkt), decode(pkt)))
                } catch (t: Throwable) {
                    Log.w(TAG, "write failed", t)
                }
            }
        }
    }

    /**
     * 发送并等待匹配的响应；超时返回 null。
     *
     * 注意：这里必须用 `CompletableFuture.get(timeout)`（阻塞带超时），
     * 而不是 `withTimeoutOrNull { fut.get() }` —— 后者无法中断阻塞中的 get()。
     */
    private suspend fun request(pkt: ByteArray, timeoutMs: Long = CMD_TIMEOUT_MS): ByteArray? {
        val f = Gaia.parse(pkt) ?: return null
        val key = f.feature
        val fut = java.util.concurrent.CompletableFuture<ByteArray>()
        responses[key] = fut
        write(pkt)
        return try {
            withContext(Dispatchers.IO) { fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        } catch (_: Throwable) {
            null
        } finally {
            responses.remove(key)
        }
    }

    private fun onBytes(value: ByteArray) {
        for (pdu in framer.feed(value, true)) onPdu(pdu)
    }

    private fun onPdu(pdu: ByteArray) {
        val f = Gaia.parse(pdu) ?: return
        emit(PodEvent.Frame("RX", Gaia.hex(pdu), "feature=${f.feature} type=${f.type} cmd=${f.command}"))
        // 唤醒等待者
        responses.remove(f.feature)?.complete(f.payload)
        dispatch(f)
    }

    private fun decode(pkt: ByteArray): String {
        val f = Gaia.parse(pkt) ?: return ""
        return "feature=${f.feature} cmd=${f.command} payload=${Gaia.hex(f.payload)}"
    }

    // ══════════════════════════════════════════════════════════════════════
    // 连接后的初始化：探测能力 → 读取全量状态 → 起轮询
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun afterConnected() {
        emitState()
        probeCapabilities()
        refreshAll()
        startPolling()
        emitState()
    }

    private suspend fun probeCapabilities() {
        val feats = HashSet<Int>()
        var cmd = Gaia.C_BASIC_GET_SUPPORTED_FEATURES
        var guard = 0
        while (guard++ < 4) {
            val p = request(Gaia.command(Gaia.F_BASIC, cmd), ANC_TIMEOUT_MS) ?: break
            // 位图分页：payload[0] bit0 = 还有下一页
            val more = (p.isNotEmpty() && (p[0].toInt() and 0x01) != 0)
            val body = if (p.isNotEmpty()) p.copyOfRange(1, p.size) else p
            feats.addAll(Gaia.parseSupportedFeatures(body))
            if (!more) break
            cmd = Gaia.C_BASIC_GET_SUPPORTED_FEATURES_NEXT
        }

        // 设备上报的电池类型（修复「右耳不显示」的第一步）
        val supported = request(Gaia.batteryGetAllV4(), ANC_TIMEOUT_MS)?.let { BatteryCodec.parseSupported(it) }

        val path = Gaia.ancPathFrom(feats).let {
            if (it != Gaia.ANC_PATH_UNKNOWN) it else (model.anc?.path?.feature ?: Gaia.ANC_PATH_UNKNOWN)
        }

        capabilities = PodCapabilities(
            features = feats,
            ancPath = path,
            batteryTypes = supported?.toList() ?: emptyList(),
            hasGain = model.dc.hasGain || Gaia.F_DAC_GAIN in feats,
            hasLed = model.dc.hasLed || Gaia.F_LED in feats,
            hasSpatial = model.dc.hasSpatial || Gaia.F_SPATIAL_AUDIO in feats,
            hasHeadTracking = model.dc.hasHeadTracking,
            hasPromptTone = model.features.promptTone || Gaia.F_VOICE in feats,
            hasPromptVolume = model.features.promptVolume || Gaia.F_VOICE in feats,
            hasLhdc = model.features.lhdc || Gaia.F_CODEC_TYPE in feats,
            hasDualConnection = model.features.dualConnection || Gaia.F_ONEBRINGTWO in feats,
            hasLowLatency = model.features.lowLatency,
            probed = true,
        )
        ancModes = model.anc?.modes ?: emptyList()
        emit(PodEvent.CapabilitiesChanged(capabilities))
        Log.i(TAG, "capabilities: $capabilities")
    }

    /** 读取全量状态。 */
    fun refreshAll() {
        scope.launch {
            refreshBattery()
            refreshAnc()
            if (capabilities.hasGain) refreshGain()
            if (capabilities.hasLed) refreshLed()
            if (capabilities.hasPromptTone) refreshPromptTone()
            if (capabilities.hasPromptVolume) refreshPromptVolume()
            if (capabilities.hasLhdc) refreshLhdc()
            if (capabilities.hasDualConnection) refreshDualConnection()
            emitState()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (connected) {
                delay(30_000L)
                refreshBattery()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 各功能：读
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 电量：先按设备上报的支持类型查询；设备不说就退回「全部类型」。
     * 这是「右耳不显示」的第一处修复——必须把要查的类型列全。
     */
    suspend fun refreshBattery() {
        val ids = capabilities.batteryTypes.takeIf { it.isNotEmpty() }?.toIntArray()
            ?: BatteryCodec.FALLBACK_QUERY_IDS
        val p = request(Gaia.batteryGet(ids), ANC_TIMEOUT_MS)
            ?: request(Gaia.batteryGetAll(), ANC_TIMEOUT_MS)
            ?: return
        applyBatteryPayload(p)
    }

    private fun applyBatteryPayload(payload: ByteArray) {
        val pairs = BatteryCodec.parse(payload)
        // 兜底：payload 只有单值且我们知道在查哪一类时，用第一个类型当 hint
        val effective = if (pairs.isEmpty() && payload.size == 1) {
            val hint = capabilities.batteryTypes.firstOrNull() ?: -1
            BatteryCodec.parse(payload, hint)
        } else pairs
        if (batteryState.applyPairs(effective)) {
            BatteryStateAccess.systemLevel = batteryState.systemLevel()
            emit(PodEvent.BatteryChanged(snapshot().battery))
        }
    }

    suspend fun refreshAnc() {
        val path = capabilities.ancPath
        val pkt = when (path) {
            Gaia.ANC_PATH_ANC_V2 -> Gaia.ancV2GetMode()
            Gaia.ANC_PATH_AUDIO_CURATION -> Gaia.audioCurationGetMode()
            Gaia.ANC_PATH_ANC_V1 -> Gaia.ancV1GetState()
            else -> return
        }
        val p = request(pkt, ANC_TIMEOUT_MS) ?: return
        applyAncPayload(p)
    }

    private fun applyAncPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        val dev = payload[0].toInt() and 0xFF
        val prof = model.anc
        ancIndex = when (capabilities.ancPath) {
            Gaia.ANC_PATH_ANC_V2 -> if (prof != null) prof.deviceToUi(dev) else dev
            Gaia.ANC_PATH_AUDIO_CURATION -> prof?.deviceToUi(dev) ?: (dev - 1)
            Gaia.ANC_PATH_ANC_V1 -> if (dev == 0) 0 else 1
            else -> -1
        }
        if (ancIndex >= 0) {
            val mode = ancModes.getOrNull(ancIndex)
            if (mode != null) emit(PodEvent.AncChanged(ancIndex, mode))
        }
    }

    suspend fun refreshGain() {
        val p = request(Gaia.gainGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        gainIndex = model.dc.gainDeviceToUi(p[0].toInt() and 0xFF)
        emit(PodEvent.GainChanged(gainIndex))
    }

    suspend fun refreshLed() {
        val p = request(Gaia.ledGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        ledOn = (p[0].toInt() and 0xFF) == 1
        emit(PodEvent.LedChanged(ledOn!!))
    }

    suspend fun refreshPromptTone() {
        val p = request(Gaia.promptToneGet(model.features.cmdVoiceGetEnable), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        promptToneOn = (p[0].toInt() and 0xFF) == 1
        emit(PodEvent.PromptToneChanged(promptToneOn!!))
    }

    suspend fun refreshPromptVolume() {
        val p = request(Gaia.promptVolumeGet(model.features.cmdVoiceGetVolume), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        promptVolumeRaw = p[0].toInt() and 0xFF
        emit(PodEvent.PromptVolumeChanged(promptVolumeRaw))
    }

    suspend fun refreshLhdc() {
        val p = request(Gaia.lhdcGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        lhdcOn = (p[0].toInt() and 0xFF) == 1
        emit(PodEvent.LhdcChanged(lhdcOn!!))
    }

    suspend fun refreshDualConnection() {
        val p = request(Gaia.dualConnectionGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        dualConnectionOn = (p[0].toInt() and 0xFF) == 1
        emit(PodEvent.DualConnectionChanged(dualConnectionOn!!))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 各功能：写（乐观更新 + 读回确认）
    // ══════════════════════════════════════════════════════════════════════

    fun setAnc(uiIndex: Int) {
        val prof = model.anc ?: return
        val dev = prof.uiToDevice(uiIndex)
        if (dev < 0) return
        scope.launch {
            when (capabilities.ancPath) {
                Gaia.ANC_PATH_ANC_V2 -> write(Gaia.ancV2SetMode(dev))
                Gaia.ANC_PATH_AUDIO_CURATION -> {
                    // EDGE 真机确认：AC 的 SET_MODE payload 是位掩码 1/2/4
                    val bit = Gaia.AC_SET_PAYLOAD.getOrNull(uiIndex) ?: dev
                    write(Gaia.audioCurationSetMode(bit))
                }
                Gaia.ANC_PATH_ANC_V1 -> write(Gaia.ancV1SetState(if (dev == 0) 0 else 1))
            }
            delay(300)
            refreshAnc()
            emitState()
        }
    }

    fun setGain(uiIndex: Int) {
        val dev = model.dc.gainUiToDevice(uiIndex)
        if (dev < 0) return
        scope.launch { write(Gaia.gainSet(dev)); delay(300); refreshGain(); emitState() }
    }

    fun setLed(on: Boolean) {
        scope.launch { write(Gaia.ledSet(if (on) 1 else 0)); delay(300); refreshLed(); emitState() }
    }

    fun setPromptTone(on: Boolean) {
        scope.launch {
            write(Gaia.promptToneSet(on, model.features.cmdVoiceSetEnable))
            delay(300); refreshPromptTone(); emitState()
        }
    }

    /** @param raw 设备端原始值（0..255），由 UI 滑条按 promptVolumeMax 换算得到 */
    fun setPromptVolumeRaw(raw: Int) {
        scope.launch {
            write(Gaia.promptVolumeSet(raw, model.features.cmdVoiceSetVolume))
            delay(300); refreshPromptVolume(); emitState()
        }
    }

    /**
     * LHDC 开关。关掉 LHDC 后耳机回到基础编码（AAC / SBC / LDAC），
     * 这正是「默认 AAC」的表现：出厂默认 LHDC 关闭。
     */
    fun setLhdc(on: Boolean) {
        scope.launch { write(Gaia.lhdcSet(on)); delay(500); refreshLhdc(); emitState() }
    }

    fun setDualConnection(on: Boolean) {
        scope.launch { write(Gaia.dualConnectionSet(on)); delay(500); refreshDualConnection(); emitState() }
    }

    /** 由系统侧（A2DP 编解码协商）回调进来，用于 UI 显示当前实际编码。 */
    fun onSystemCodecChanged(codecName: String) {
        activeCodec = codecName
        emitState()
    }

    /** 由系统侧（低延迟开关）回调进来。 */
    fun onLowLatencyChanged(on: Boolean) {
        lowLatencyOn = on
        emit(PodEvent.LowLatencyChanged(on))
        emitState()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 响应分发（除了唤醒 request()，还要处理主动通知）
    // ══════════════════════════════════════════════════════════════════════

    private fun dispatch(f: Gaia.Frame) {
        when (f.feature) {
            Gaia.F_BATTERY -> if (f.command == Gaia.C_BATT_GET_BATTERY_LEVELS ||
                f.command == Gaia.C_BATT_GET_BATTERY_LEVELS_V4
            ) {
                applyBatteryPayload(f.payload)
            }
            Gaia.F_ANC_V2 -> if (f.command == Gaia.C_ANC2_GET_CURRENT_MODE ||
                f.command == Gaia.C_ANC2_SET_CURRENT_MODE
            ) {
                applyAncPayload(f.payload)
            }
            Gaia.F_AUDIO_CURATION -> if (f.command == Gaia.C_AC_GET_CURRENT_MODE) {
                applyAncPayload(f.payload)
            }
            Gaia.F_ANC -> if (f.command == Gaia.C_ANC1_GET_ANC_STATE) applyAncPayload(f.payload)
            Gaia.F_DAC_GAIN -> if (f.command == Gaia.C_DAC_GET_GAIN && f.payload.isNotEmpty()) {
                gainIndex = model.dc.gainDeviceToUi(f.payload[0].toInt() and 0xFF)
                emit(PodEvent.GainChanged(gainIndex))
            }
            Gaia.F_LED -> if (f.command == Gaia.C_LED_GET_STATE && f.payload.isNotEmpty()) {
                ledOn = (f.payload[0].toInt() and 0xFF) == 1
                emit(PodEvent.LedChanged(ledOn!!))
            }
            Gaia.F_CODEC_TYPE -> if (f.command == Gaia.C_CODEC_GET_LHDC_STATE && f.payload.isNotEmpty()) {
                lhdcOn = (f.payload[0].toInt() and 0xFF) == 1
                emit(PodEvent.LhdcChanged(lhdcOn!!))
            }
            Gaia.F_ONEBRINGTWO -> if (f.command == Gaia.C_OBT_GET_STATE && f.payload.isNotEmpty()) {
                dualConnectionOn = (f.payload[0].toInt() and 0xFF) == 1
                emit(PodEvent.DualConnectionChanged(dualConnectionOn!!))
            }
            Gaia.F_VOICE -> {
                if (f.command == model.features.cmdVoiceGetEnable && f.payload.isNotEmpty()) {
                    promptToneOn = (f.payload[0].toInt() and 0xFF) == 1
                    emit(PodEvent.PromptToneChanged(promptToneOn!!))
                } else if (f.command == model.features.cmdVoiceGetVolume && f.payload.isNotEmpty()) {
                    promptVolumeRaw = f.payload[0].toInt() and 0xFF
                    emit(PodEvent.PromptVolumeChanged(promptVolumeRaw))
                }
            }
            Gaia.F_BASIC -> if (f.command == Gaia.C_BASIC_GET_SUPPORTED_FEATURES) {
                // request() 已经消费；这里只做通知型位图的增量合并
                capabilities = capabilities.copy(
                    features = capabilities.features + Gaia.parseSupportedFeatures(f.payload),
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 9ECA 私有协议（中科蓝讯系：音源切换 / EQ / MIC）
    // ══════════════════════════════════════════════════════════════════════

    fun srcGetAudioSource(seq: Int) = write(SrcProtocol.command(SrcProtocol.CMD_GET_AUDIO_SOURCE, seq, null))
    fun srcSetAudioSource(seq: Int, sourceId: Int) =
        write(SrcProtocol.setAudioSource(seq, sourceId))

    companion object {
        /** 供 hook 进程判断电量是否要写进系统蓝牙栈 */
        fun systemBatteryLevel(): Int = BatteryStateAccess.systemLevel
    }
}

/** 让 hook 进程读到当前系统电量，避免直接暴露内部对象。 */
object BatteryStateAccess {
    @Volatile var systemLevel: Int = BATTERY_UNKNOWN
}
