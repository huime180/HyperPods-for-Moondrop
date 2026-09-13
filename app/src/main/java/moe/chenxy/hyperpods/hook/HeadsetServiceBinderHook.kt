/*
 * HyperPods for Moondrop — com.xiaomi.bluetooth 进程：伪装 MIUI 耳机 AIDL 服务的**服务端实现**
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * ── 为什么要有这个文件（真机 DEX 实证） ──────────────────────────────────────
 * 系统设置页（com.android.settings）通过 AIDL `IMiuiHeadsetService` 访问小米蓝牙服务：
 *   客户端（settings / milink）：com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy
 *   服务端（com.xiaomi.bluetooth）：com.android.bluetooth.ble.app.headset.BluetoothHeadsetService$HeadsetBinder
 *                                   extends com.android.bluetooth.ble.app.q$a   ← 混淆后的 AIDL Stub
 *
 * 我们此前**只**挂了 settings 侧的 $Stub$Proxy，而真机上服务端实现整条链是另一个进程的
 * 另一套类（本 ROM 把接口混淆成 `q`）。用户报「设置 hook 没有效果」正是这条链路没接上。
 * 参考实现（PuddingPods/OppoPods 构建）在旧 ROM 上挂的是
 * `com.android.bluetooth.ble.app.headset.BinderC6776v`（那张 ROM 上该 Binder 子类的 R8 名）
 * + `IMiuiHeadsetService.Stub.onTransact`；本 ROM 上对应物就是本文件下面的 HeadsetBinder。
 * 两代 ROM 的候选表现在集中在 RomProfile（BT_BINDER 组），本文件只消费结论。
 *
 * ── 为什么不挂 onTransact 来做拦截 ───────────────────────────────────────────
 * AIDL 的 onTransact 靠 `TRANSACTION_<方法名>` 常量分发，而 R8 已把那些常量全部内联、
 * 静态字段被剥掉（本 ROM 的 IMiuiHeadsetService$Stub 静态字段为空，已用 DEX 核对），
 * 事务码无法静态取得。好在 HeadsetBinder **直接实现了每一个接口方法**，所以直接挂
 * 这些实现方法即可得到与「拦 onTransact」完全等价的效果，而且不依赖混淆名、不解析 Parcel。
 * onTransact 仍然挂了一条**只观察不改写**的诊断 hook：它把事务码与实现方法对应起来打进
 * logcat（`TRANSACTION code=N -> checkSupport`），这样以后真要在 onTransact 层做别的事，
 * 事务码是从设备上量出来的，不是猜的。
 *
 * 已用 dex 工具核对（本 ROM，com.xiaomi.bluetooth）：
 *   BluetoothHeadsetService$HeadsetBinder 声明了
 *     checkSupport(BluetoothDevice):String        getDeviceInfo(String):String
 *     isSupportAudioSwitch(String):String         isMiTWS(String):Z  checkIsMiTWS(String):Z
 *     getRingFindState(String):Z                  setCommonCommand(int,String,BluetoothDevice):String
 *     changeAncMode(int,BluetoothDevice):V        changeAncLevel(String,BluetoothDevice):V
 *     connect(BluetoothDevice):V                  getDeviceConfig(BluetoothDevice):V
 *     getCommonConfig(BluetoothDevice,String):V   ringFindForAirPods/getRingFindStateForThirdParty...
 *   其父类 q$a 声明 onTransact(int,Parcel,Parcel,int):Z
 *
 * ⚠ 全部 runCatching 包裹：缺类 / 缺方法只跳过该条 hook。
 * ⚠ 广播一律 setPackage(...)。
 */
package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

@SuppressLint("MissingPermission")
object HeadsetServiceBinderHook {
    private const val TAG = "HyperPods-HeadsetBinder"

    private const val PKG_APP = BuildConfig.APPLICATION_ID

    /**
     * 服务端实现的候选类 —— **由 RomProfile 按检测到的 ROM 代数给药**（不再硬编码单一代的名字）：
     *   · HyperOS 4（本机 APK 已核对）：headset.BluetoothHeadsetService$HeadsetBinder
     *     继承混淆后的 AIDL Stub `com.android.bluetooth.ble.app.q$a`（R8 名随 build 变）；
     *   · HyperOS 3（unverified-on-device，取自旧 ROM 参考实现 PuddingPods）：
     *     headset.BinderC6776v / headset.BluetoothHeadsetService / headset.v。
     * RomProfile 会把「本代主档」排在最前，其余代数作为兜底，因此探测错了也只是退化到旧行为。
     */
    private val BINDER_CLASSES: List<String>
        get() = RomProfile.bluetoothBinderClasses

    private const val FAKE_DEVICE_ID = "01010607"
    private const val FAKE_SUPPORT = "$FAKE_DEVICE_ID,000000000000000010000000"

    /** setCommonCommand 的「读」命令码：参考实现里 102 走读、其余走写。 */
    private const val COMMON_COMMAND_READ = 102

    private val hooked = LinkedHashSet<String>()
    private val transactCodeToMethod = ConcurrentHashMap<Int, String>()
    private val seenCodes = LinkedHashSet<Int>()

    /** onTransact 正在处理的（线程本地性不保证，仅用于诊断映射）。 */
    @Volatile
    private var pendingTransactCode = -1

    @Volatile
    private var processContext: Context? = null

    fun install(ctx: HookContext) {
        Log.d(TAG, "installing headset service binder hooks (${RomProfile.summary()})")
        Log.d(TAG, "binder candidates: ${BINDER_CLASSES.joinToString()}")
        installResultHooks(ctx)
        installCommandHooks(ctx)
        installNoopHooks(ctx)
        installTransactObserver(ctx)
    }

    fun reset() {
        hooked.clear()
        transactCodeToMethod.clear()
        synchronized(seenCodes) { seenCodes.clear() }
        pendingTransactCode = -1
        processContext = null
    }

    // ── 返回值直通：让系统认为这台设备是受支持的小米 TWS ──────────────────────

    private fun installResultHooks(ctx: HookContext) {
        installResult(ctx, "checkSupport", arrayOf(BluetoothDevice::class.java)) { FAKE_SUPPORT }
        installResult(ctx, "getDeviceInfo", arrayOf(String::class.java)) { FAKE_SUPPORT }
        installResult(ctx, "isSupportAudioSwitch", arrayOf(String::class.java)) { "1" }
        installResult(ctx, "isMiTWS", arrayOf(String::class.java)) { true }
        installResult(ctx, "checkIsMiTWS", arrayOf(String::class.java)) { true }
        installResult(ctx, "getRingFindState", arrayOf(String::class.java)) { false }
        installResult(
            ctx, "setCommonCommand",
            arrayOf(Int::class.javaPrimitiveType!!, String::class.java, BluetoothDevice::class.java)
        ) { args ->
            if (args.getOrNull(0) as? Int == COMMON_COMMAND_READ) "0" else "1"
        }
    }

    // ── 命令：吞掉原生调用，把本模块 UI 下标广播给应用进程 ─────────────────────

    private fun installCommandHooks(ctx: HookContext) {
        installCommand(
            ctx, "changeAncMode",
            arrayOf(Int::class.javaPrimitiveType!!, BluetoothDevice::class.java)
        ) { args -> uiIndexFromMiui(args.getOrNull(0) as? Int) }
        installCommand(
            ctx, "changeAncLevel",
            arrayOf(String::class.java, BluetoothDevice::class.java)
        ) { args -> uiIndexFromLevel(args.getOrNull(0) as? String) }
    }

    // ── no-op：真机没有 MMA 后端，避免系统页面卡在「连接中」 ───────────────────

    private fun installNoopHooks(ctx: HookContext) {
        installNoop(ctx, "connect", arrayOf(BluetoothDevice::class.java))
        installNoop(ctx, "getDeviceConfig", arrayOf(BluetoothDevice::class.java))
        installNoop(ctx, "getCommonConfig", arrayOf(BluetoothDevice::class.java, String::class.java))
    }

    // ── 诊断：把事务码与实现方法对应起来（只观察，绝不改写） ───────────────────

    private fun installTransactObserver(ctx: HookContext) {
        val params = arrayOf(
            Int::class.javaPrimitiveType!!, Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType!!
        )
        runCatching {
            val method = firstResolvable(ctx, "onTransact", params)
                ?: throw NoSuchMethodException("onTransact/4")
            if (!hooked.add("transact:" + method.toGenericString())) return@runCatching
            ctx.hookBefore(method) {
                val code = args.getOrNull(0) as? Int ?: return@hookBefore
                pendingTransactCode = code
                val first = synchronized(seenCodes) { seenCodes.add(code) }
                if (first) Log.i(TAG, "onTransact code=$code seen (first time, ${method.declaringClass.name})")
            }
            Log.d(TAG, "hooked ${method.declaringClass.name}#onTransact (diagnostic only)")
        }.onFailure { Log.w(TAG, "hook <headset binder>.onTransact skipped", it) }
    }

    /** 在所有候选类里挑第一个能解析出该方法签名的类；都没有就返回 null。 */
    private fun firstResolvable(
        ctx: HookContext,
        methodName: String,
        params: Array<Class<*>>
    ): Method? = BINDER_CLASSES.asSequence()
        .filter { ctx.findClassOrNull(it) != null }
        .mapNotNull { className -> runCatching { resolve(ctx, className, methodName, params) }.getOrNull() }
        .firstOrNull()

    // ── 通用包装 ─────────────────────────────────────────────────────────────

    private fun installResult(
        ctx: HookContext,
        methodName: String,
        params: Array<Class<*>>,
        provide: (List<Any?>) -> Any
    ) {
        runCatching {
            val method = firstResolvable(ctx, methodName, params)
                ?: throw NoSuchMethodException("$methodName/${params.size}")
            if (!hooked.add("after:" + method.toGenericString())) return@runCatching
            ctx.hookAfter(method) {
                if (!isOurs(args)) return@hookAfter
                noteBinderMethod(methodName)
                val value = provide(args)
                coerceToReturnType(method.returnType, value)?.let { result = it }
                Log.d(TAG, "$methodName forced=$value args=${describeArgs(args)}")
            }
            Log.d(TAG, "hooked ${method.declaringClass.name}#$methodName")
        }.onFailure { Log.w(TAG, "hook <headset binder>.$methodName skipped", it) }
    }

    private fun installCommand(
        ctx: HookContext,
        methodName: String,
        params: Array<Class<*>>,
        uiIndex: (List<Any?>) -> Int?
    ) {
        runCatching {
            val method = firstResolvable(ctx, methodName, params)
                ?: throw NoSuchMethodException("$methodName/${params.size}")
            if (!hooked.add("before:" + method.toGenericString())) return@runCatching
            ctx.hookBefore(method) {
                if (!isOurs(args)) return@hookBefore
                noteBinderMethod(methodName)
                val index = uiIndex(args) ?: return@hookBefore
                sendAncSelect(index)
                result = null
                Log.i(TAG, "$methodName handled -> uiIndex=$index args=${describeArgs(args)}")
            }
            Log.d(TAG, "hooked ${method.declaringClass.name}#$methodName as command")
        }.onFailure { Log.w(TAG, "hook <headset binder>.$methodName command skipped", it) }
    }

    private fun installNoop(ctx: HookContext, methodName: String, params: Array<Class<*>>) {
        runCatching {
            val method = firstResolvable(ctx, methodName, params)
                ?: throw NoSuchMethodException("$methodName/${params.size}")
            if (!hooked.add("before:" + method.toGenericString())) return@runCatching
            ctx.hookBefore(method) {
                if (!isOurs(args)) return@hookBefore
                noteBinderMethod(methodName)
                result = null
                Log.d(TAG, "$methodName swallowed args=${describeArgs(args)}")
            }
            Log.d(TAG, "hooked ${method.declaringClass.name}#$methodName as no-op")
        }.onFailure { Log.w(TAG, "hook <headset binder>.$methodName no-op skipped", it) }
    }

    /** 先按声明签名找，找不到再沿继承链按参数个数找。 */
    private fun resolve(ctx: HookContext, className: String, methodName: String, params: Array<Class<*>>): Method =
        runCatching { ctx.findMethod(className, methodName, *params) }.getOrNull()
            ?: runCatching { ctx.findAnyMethod(className, methodName, params.size) }.getOrNull()
            ?: throw NoSuchMethodException("$className#$methodName/${params.size}")

    /** 与 MiLinkServiceHook 同款保护：类型对不上就保留原返回值，绝不让框架 ClassCastException。 */
    private fun coerceToReturnType(returnType: Class<*>, value: Any): Any? {
        if (returnType.name == "void" || returnType == java.lang.Void::class.java) return null
        return when {
            returnType == Int::class.javaPrimitiveType -> value as? Int
            returnType == Boolean::class.javaPrimitiveType -> value as? Boolean
            returnType == Long::class.javaPrimitiveType -> value as? Long
            returnType == IntArray::class.java -> (value as? List<*>)?.mapNotNull { it as? Int }?.toIntArray()
            returnType.isInstance(value) -> value
            else -> null
        }
    }

    private fun noteBinderMethod(methodName: String) {
        val code = pendingTransactCode
        if (code < 0) return
        pendingTransactCode = -1
        if (transactCodeToMethod.putIfAbsent(code, methodName) == null) {
            Log.i(TAG, "TRANSACTION code=$code -> $methodName")
        }
    }

    // ── 设备判定 ─────────────────────────────────────────────────────────────

    private fun isOurs(args: List<Any?>): Boolean {
        (args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice)?.let { if (isMoondrop(it)) return true }
        args.forEach { arg -> if (arg is String && isMoondropToken(arg)) return true }
        return false
    }

    private fun isMoondrop(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        if (isMoondropToken(SystemApisUtils.deviceAddress(device))) return true
        return MoondropModels.match(SystemApisUtils.deviceName(device)) != null
    }

    /**
     * 参数可能是 MAC、Device ID（"01010607"）或能力串。
     * 拿不准时用蓝牙栈把地址解析成设备名再判一次——这样不依赖任何本进程没收到过的广播。
     */
    private fun isMoondropToken(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (value == FAKE_DEVICE_ID || value.startsWith(FAKE_DEVICE_ID)) return true
        if (MoondropModels.match(value) != null) return true
        val name = runCatching {
            val manager = processContext()?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.getRemoteDevice(value)?.name
        }.getOrNull()
        return MoondropModels.match(name) != null
    }

    private fun processContext(): Context? {
        processContext?.let { return it }
        val ctx = SystemApisUtils.currentApplication() ?: return null
        val app = ctx.applicationContext ?: ctx
        processContext = app
        return app
    }

    private fun sendAncSelect(uiIndex: Int) {
        val ctx = processContext() ?: run {
            Log.w(TAG, "sendAncSelect skipped: no Context uiIndex=$uiIndex")
            return
        }
        runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.ANC_SELECT).apply {
                setPackage(PKG_APP)
                putExtra(HyperPodsAction.EXTRA_STATUS, uiIndex)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.i(TAG, "ANC_SELECT uiIndex=$uiIndex -> $PKG_APP")
        }.onFailure { Log.w(TAG, "ANC_SELECT broadcast failed", it) }
    }

    // ── MIUI 档位 <-> 本模块 UI 下标（与 SettingsHeadsetHook 同构） ─────────────

    private fun uiIndexFromMiui(mode: Int?): Int? = when (mode) {
        null -> null
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 4
        else -> 0
    }

    private fun uiIndexFromLevel(level: String?): Int? {
        if (level == null) return null
        return when {
            level.startsWith("01") -> 1
            level.startsWith("02") -> 2
            level.startsWith("03") -> 3
            level.startsWith("04") -> 4
            else -> 0
        }
    }

    private fun describeArgs(args: List<Any?>): String = args.joinToString(prefix = "[", postfix = "]") { arg ->
        when (arg) {
            null -> "null"
            is BluetoothDevice -> "BluetoothDevice(${SystemApisUtils.deviceAddress(arg)},${SystemApisUtils.deviceName(arg)})"
            is Int -> arg.toString()
            is String -> arg
            else -> arg.javaClass.simpleName
        }
    }
}
