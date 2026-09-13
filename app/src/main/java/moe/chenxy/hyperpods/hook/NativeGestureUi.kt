/*
 * HyperPods for Moondrop — 原生耳机页「手势控制」段：藏掉厂商的，换成我们自己的
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 用户报告：「蓝牙设置未 hook 成功，手势控制还是原来的」。
 *
 * ── 真机 / DEX / 资源取证（HyperOS 4 平板）─────────────────────────────────────
 * 原生页的手势段是 **res/xml/headsetlayout.xml 里 key = "key_config" 的那条 preference**
 * （位于 PreferenceCategory key="switchConfig" 下），点击走
 * `MiuiHeadsetFragment.onPreferenceTreeClick` → `gotoKeyConfigFragment()` →
 * `MiuiHeadsetActivity.changeFragment(MiuiHeadsetKeyConfigFragment)`。
 *
 * 那个子页的数据源是 **AIDL 代理**（不是我们的 GAIA 通道）：
 *   MiuiHeadsetKeyConfigFragment.initKeyConfig()
 *     → getRadioButtonConfig() → IMiuiHeadsetService.setCommonCommand(106, "", device) : String
 *     → 期望 12 个十六进制字符（每个字符 = 一个按键槽的动作 id）
 *     → hexToByteArray() → 写入 mLeftDoubleKey / mRightDoubleKey / mLeftTripleKey /
 *       mRightTripleKey / mDropdownLeftKey(idx4) / mDropdownRightKey(idx8) 六个 DropDownPreference
 *   写回：saveCurrentKeyConfig() → MiuiHeadsetActivity.setDeviceConfig(bundle)
 *         / IMiuiHeadsetService.setFunKey(int, int, device)
 *
 * ── 为什么不做「驱动厂商那套 UI」（方案 1 被否决）────────────────────────────
 * 厂商的按键模型与 Pudding 的真实协议 **不是一回事**，硬映射只会显示错的：
 *   · 厂商 6 个槽：左/右 × 双击 / 三击 / 长按（只有一个长按）；
 *   · 本耳机 feature 22 TOUCHV2：5 个槽（单击 / 双击 / 三击 / 长按1秒 / 长按3秒）× 双耳 = 10 个半字节，
 *     且动作 id 空间（0..7：无/播放暂停/上一曲/下一曲/音量±/语音助手/降噪切换）与厂商下拉项
 *     （press_key_entries 资源数组）也不同。
 *   ⇒ 「单击」和「长按3秒」在厂商 UI 里根本没有槽位；用 12 字符配置串硬拼只会让页面显示
 *     一份**与实际不符**的手势状态。用户要的是「不要再显示原来的（陈旧/错误）状态」。
 *   另外那一串 12 字符的语义只从字节码里读出 6 个下标（0/1/2/3/4/8），其余 6 个下标无证据，
 *   不猜。
 *
 * ── 因此采用方案 2：藏掉厂商的手势段，托管我们自己的控件 ────────────────────────
 *   1) `Preference.setVisible(false)` 隐藏 key="key_config" 这条 preference
 *      （厂商那条入口连同它可能携带的陈旧 summary 一起消失）；
 *   2) 在**卡片区**（`groupAncCard` 之后，和 NativeThreeModeAncUi 同一层）插入我们的
 *      「手势控制」卡片：5 行 = 5 种手势，每行显示左耳/右耳当前动作；
 *      点某个动作 → 弹动作选择器 → 广播 GESTURE_SELECT 给应用进程（真值只在应用进程）；
 *   3) 应用进程通过 GESTURE_CHANGED 回灌真实配置（5 字节 TOUCHV2），这里刷新显示。
 *      没同步到之前显示「未同步」，**绝不显示厂商那套默认值**。
 *   4) 卡片底部一个入口「打开 App 手势设置」→ 广播 SHOW_UI。
 *
 * 依赖的应用侧接线（本次只加常量，pods/ControlBridge.kt 由上层补）：
 *   · GESTURE_CHANGED  (app → settings) : EXTRA_GESTURE_PAYLOAD = ByteArray(5)
 *   · GESTURE_SELECT   (settings → app) : EXTRA_GESTURE_SLOT(Int 0..4) + EXTRA_GESTURE_EAR(Int 0/1)
 *                                         + EXTRA_STATUS(Int = 动作 id 0..7)
 *   · REQUEST_GESTURE  (settings → app) : 请求重放一次当前手势配置
 *
 * ⚠ 不触碰 ui/GesturePage.kt、pods/MoondropLink.kt、core/Gaia.kt（应用侧手势页已正常工作）。
 * ⚠ 所有视图引用都是弱引用 + 全程 runCatching：页面销毁、厂商改布局都只是「这条路径失效」。
 */
package moe.chenxy.hyperpods.hook

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import moe.chenxy.hyperpods.core.Gaia

object NativeGestureUi {
    private const val TAG = "HyperPods-GestureUi"

    /** 厂商手势段的 preference key（res/xml/headsetlayout.xml，H4 实证）。 */
    private const val PREF_KEY_GESTURE = "key_config"

    /**
     * 卡片区里「我们的手势卡片」插在哪个容器之后（按 entry 名找，先精确后兜底）。
     * H4 实测：mainlayout → linear_layout 的孩子依次是
     * picAnimation / groupBatteryCard / groupAncCard / groupRenameCard，
     * 所以在 groupAncCard 后面插 = 紧接降噪卡片。
     */
    private val ANCHOR_ENTRY_NAMES = listOf("groupAncCard", "groupBatteryCard", "groupRenameCard")

    private val COLOR_SELECTED = 0xFF007AFF.toInt()
    private val COLOR_SELECTED_BG = 0x1F007AFF
    private val COLOR_NORMAL = 0xFF4C4C4C.toInt()
    private val COLOR_NORMAL_BG = 0x14000000

    private var containerRef: WeakReference<LinearLayout>? = null
    private var rootRef: WeakReference<View>? = null

    /** 被我们隐藏的厂商 preference（避免每 3s 重复调用 setVisible）。 */
    private var hiddenPrefRef: WeakReference<Any>? = null

    private var vendorHiddenLogged = false

    /** 当前手势配置（来自应用进程的 GESTURE_CHANGED）。null = 还没同步到。 */
    private var conf: Gaia.GestureConf? = null

    /** 每行「左/右」两个动作按钮：index = slot.index * 2 + earOrdinal。 */
    private var valueButtons: List<TextView> = emptyList()

    private var selectCallback: ((Int, Int, Int) -> Unit)? = null
    private var openAppCallback: (() -> Unit)? = null

    fun install(
        fragment: Any?,
        current: Gaia.GestureConf?,
        onOpenApp: () -> Unit,
        onSelect: (Int, Int, Int) -> Unit
    ): Boolean {
        if (current != null) conf = current
        openAppCallback = onOpenApp
        selectCallback = onSelect
        val root = runCatching { callMethod(fragment, "getView") as? View }.getOrNull() ?: return false
        rootRef = WeakReference(root)

        hideVendorGesturePreference(fragment)

        containerRef?.get()?.let { alive ->
            if (alive.parent != null) {
                refresh(conf)
                reassert()
                return true
            }
        }
        return runCatching { buildAndInsert(root) }
            .onFailure { Log.w(TAG, "install gesture ui failed", it) }
            .getOrDefault(false)
    }

    /** 跟随应用进程同步过来的配置刷新（不重建视图）。 */
    fun refresh(current: Gaia.GestureConf?) {
        conf = current
        runCatching { paint() }.onFailure { Log.w(TAG, "refresh gesture ui failed", it) }
    }

    /** 应用进程还没同步过来时也要能把状态显示对（显示「未同步」而不是厂商默认值）。 */
    fun hasConf(): Boolean = conf != null

    /** 页面存活期间的周期性重新声明：厂商会把 preference 放回来 / 把我们的卡片藏掉。 */
    fun reassert() {
        val container = containerRef?.get() ?: return
        if (container.parent == null) return
        runCatching {
            if (container.visibility != View.VISIBLE) {
                container.visibility = View.VISIBLE
                Log.d(TAG, "reasserted visibility of gesture ui")
            }
            val pref = hiddenPrefRef?.get()
            if (pref != null && runCatching { callMethod(pref, "isVisible") as? Boolean }.getOrNull() != false) {
                runCatching { callMethod(pref, "setVisible", false) }
                Log.d(TAG, "re-hid vendor gesture preference")
            }
            paint()
        }.onFailure { Log.w(TAG, "reassert gesture ui failed", it) }
    }

    fun reset() {
        containerRef = null
        rootRef = null
        hiddenPrefRef = null
        vendorHiddenLogged = false
        conf = null
        valueButtons = emptyList()
        selectCallback = null
        openAppCallback = null
    }

    fun isAttached(): Boolean = containerRef?.get()?.parent != null

    /** 心跳：一眼看出厂商那条入口藏掉没有、我们的卡片装上没有、配置同步到没有。 */
    fun stateSummary(): String =
        "attached=" + isAttached() +
            " vendorHidden=" + (hiddenPrefRef?.get() != null) +
            " conf=" + (conf?.toString() ?: "null")

    // ── 隐藏厂商手势段 ────────────────────────────────────────────────────────

    /**
     * `findPreference("key_config").setVisible(false)`。
     * 这是 PreferenceFragmentCompat 的公开方法，反射调用；拿不到就只记日志
     * （不 fallback 去藏 RecyclerView 里的行 —— RecyclerView 的子视图会被回收，
     *  直接动它的孩子反而会把列表搞乱）。
     */
    private fun hideVendorGesturePreference(fragment: Any?) {
        if (fragment == null) return
        if (hiddenPrefRef?.get() != null) return
        runCatching {
            val pref = callMethod(fragment, "findPreference", PREF_KEY_GESTURE)
            if (pref == null) {
                Log.w(TAG, "vendor gesture preference '$PREF_KEY_GESTURE' not found on this page")
                return
            }
            callMethod(pref, "setVisible", false)
            hiddenPrefRef = WeakReference(pref)
            if (!vendorHiddenLogged) {
                vendorHiddenLogged = true
                Log.i(
                    TAG,
                    "vendorGestureHidden=true reason=preference:$PREF_KEY_GESTURE " +
                        "(framework key-config page replaced by our hosted gesture control)"
                )
            }
        }.onFailure { Log.w(TAG, "hide vendor gesture preference failed", it) }
    }

    // ── 组装 ─────────────────────────────────────────────────────────────────

    private fun buildAndInsert(root: View): Boolean {
        val context = root.context ?: return false
        val container = buildControl(context) ?: return false

        val anchor = findAnchor(root)
        val anchorParent = anchor?.parent as? ViewGroup
        val insertParent: ViewGroup
        val insertIndex: Int
        if (anchor != null && anchorParent != null) {
            insertParent = anchorParent
            insertIndex = anchorParent.indexOfChild(anchor) + 1
        } else {
            val host = findHostContainer(root)
            if (host == null) {
                Log.w(TAG, "no injectable container for gesture ui")
                return false
            }
            insertParent = host
            insertIndex = host.childCount
        }

        insertParent.addView(container, insertIndex.coerceIn(0, insertParent.childCount))
        containerRef = WeakReference(container)
        container.post { reassert() }
        Log.i(
            TAG,
            "gesture ui installed into ${insertParent.javaClass.name} entry=${entryName(insertParent)} " +
                "index=$insertIndex anchor=${entryName(anchor)} " + stateSummary()
        )
        return true
    }

    private fun buildControl(context: Context): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16f), dp(context, 10f), dp(context, 16f), dp(context, 12f))
        }

        container.addView(titleView(context, "手势控制（HyperPods）"))

        val buttons = ArrayList<TextView>(Gaia.GestureSlot.entries.size * 2)
        Gaia.GestureSlot.entries.forEach { slot ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val name = TextView(context).apply {
                text = slot.labelZh
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(COLOR_NORMAL)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            row.addView(name)
            Gaia.Ear.entries.forEach { ear ->
                val tv = actionChip(context, slot, ear)
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(context, 6f)
                })
                buttons.add(tv)
            }
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 6f) })
        }
        valueButtons = buttons

        val footer = TextView(context).apply {
            text = if (conf == null) "尚未同步（点此打开 App 手势设置）" else "打开 App 手势设置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(COLOR_SELECTED)
            gravity = Gravity.CENTER
            minHeight = dp(context, 32f)
            isClickable = true
            setOnClickListener { runCatching { openAppCallback?.invoke() } }
        }
        container.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 8f) })

        paint()
        return container
    }

    private fun titleView(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(COLOR_NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun actionChip(context: Context, slot: Gaia.GestureSlot, ear: Gaia.Ear): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minHeight = dp(context, 36f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onChipClicked(slot, ear) }
        }

    private fun paint() {
        if (valueButtons.size != Gaia.GestureSlot.entries.size * 2) return
        var i = 0
        Gaia.GestureSlot.entries.forEach { slot ->
            Gaia.Ear.entries.forEach { ear ->
                val tv = valueButtons[i++]
                val id = conf?.action(slot, ear)
                tv.text = when {
                    id == null -> ear.labelZh + "：未同步"
                    else -> ear.labelZh + "：" + Gaia.TouchActions.matchOrUnknown(id)
                }
                val known = id != null
                tv.setTextColor(if (known) COLOR_SELECTED else COLOR_NORMAL)
                tv.background = pill(context(tv), if (known) COLOR_SELECTED_BG else COLOR_NORMAL_BG)
            }
        }
    }

    private fun context(view: View): Context = view.context

    private fun pill(context: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, 18f).toFloat()
        setColor(color)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // ── 交互 ─────────────────────────────────────────────────────────────────

    /**
     * 点某个「手势 × 耳朵」→ 弹动作选择器（顺序 = 官方 App 选择器顺序，见 Gaia.TouchActions.ALL）。
     * 选中后立刻乐观刷新本行，并把真值广播给应用进程（真值只在应用进程，由它读-改-写下发）。
     */
    private fun onChipClicked(slot: Gaia.GestureSlot, ear: Gaia.Ear) {
        val view = containerRef?.get() ?: return
        val context = view.context ?: return
        runCatching {
            val actions = Gaia.TouchActions.ALL
            // 显式 Array<CharSequence>：setSingleChoiceItems 的参数是 CharSequence[]。
            val labels: Array<CharSequence> = actions.map { it.labelZh as CharSequence }.toTypedArray()
            val current = conf?.action(slot, ear) ?: -1
            val checked = actions.indexOfFirst { it.id == current }
            AlertDialog.Builder(context)
                .setTitle("${slot.labelZh} · ${ear.labelZh}")
                .setSingleChoiceItems(labels, checked) { dialog: DialogInterface, which: Int ->
                    val action = actions.getOrNull(which)
                    if (action != null) applySelection(slot, ear, action.id)
                    runCatching { dialog.dismiss() }
                }
                .setNegativeButton("取消", null)
                .show()
        }.onFailure { Log.w(TAG, "gesture action picker failed", it) }
    }

    private fun applySelection(slot: Gaia.GestureSlot, ear: Gaia.Ear, actionId: Int) {
        val current = conf
        // 还没同步到整份配置时**不臆造**另外 4 个槽位（那样会把「未知」显示成「无」）：
        // 只把选择广播出去，等应用进程回灌 GESTURE_CHANGED 再显示。
        conf = if (current != null) current.with(slot, ear, actionId) else null
        runCatching { paint() }.onFailure { Log.w(TAG, "paint after gesture select failed", it) }
        Log.i(TAG, "gesture selected slot=${slot.index}(${slot.labelZh}) ear=${ear.name} action=$actionId")
        runCatching { selectCallback?.invoke(slot.index, ear.ordinal, actionId) }
            .onFailure { Log.w(TAG, "gesture select callback failed", it) }
    }

    // ── 视图查找 ─────────────────────────────────────────────────────────────

    /** 卡片区里的锚点：按 entry 名找（先精确，再逐个兜底）。 */
    private fun findAnchor(root: View): View? {
        ANCHOR_ENTRY_NAMES.forEach { wanted ->
            findByEntryName(root, wanted)?.let { return it }
        }
        return null
    }

    private fun findByEntryName(root: View, wanted: String): View? {
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            if (entryName(view) == wanted) return view
            if (view is ViewGroup) for (c in 0 until view.childCount) queue.add(view.getChildAt(c))
        }
        return null
    }

    private fun findHostContainer(root: View): ViewGroup? {
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            if (view is LinearLayout &&
                view.orientation == LinearLayout.VERTICAL &&
                view.childCount >= 1 &&
                view.visibility == View.VISIBLE
            ) {
                return view
            }
            if (view is ViewGroup) for (c in 0 until view.childCount) queue.add(view.getChildAt(c))
        }
        return root as? ViewGroup
    }

    private fun entryName(view: View?): String? {
        if (view == null) return null
        if (view.id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }
}
