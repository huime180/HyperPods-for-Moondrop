/*
 * HyperPods for Moondrop — 在 HyperOS 原生耳机页里装一套「三档降噪」控件（**替换**框架的）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 需求（用户）：
 *   「PuddingPods 把降噪设置适配进系统界面了，仿照一下」
 *   「蓝牙设置中是有降噪切换的按钮的，会多出新的按钮，在原有的按钮上修改」
 *   ⇒ 目标不是「在旁边多加一套」，而是**把框架自己的 ANC 控件整块换掉**。
 *
 * ── 真机取证（2026-09-14，Xiaomi Pad 8 Pro / HyperOS 4，logcat HyperPods-AncUi view tree dump）────
 * 原生耳机页 res/layout/headsetlayout.xml 的真实结构（entry 名来自 View.resources.getResourceEntryName）：
 *
 *   LinearLayout|mainlayout
 *    └ LinearLayout|linear_layout
 *       ├ LinearLayout|groupBatteryCard  → CardView|batteryCard
 *       ├ LinearLayout|groupAncCard      → CardView|ancCard
 *       │    └ LinearLayout|anclayout                       ★ 框架的整块 ANC 控件
 *       │         ├ LinearLayout|ancLayoutInfo              （三档选择器）
 *       │         │    ├ LinearLayout|transport             （图标 + 文案）
 *       │         │    ├ LinearLayout|openAnc
 *       │         │    └ LinearLayout|closeAnc
 *       │         ├ LinearLayout|ancAdjust                  （档位滑杆行）
 *       │         │    ├ MiuiHeadsetAncAdjustView|ancAdjustView
 *       │         │    └ MiuiHeadsetAncAdjustView|ancAdjustView2   (GONE)
 *       │         ├ LinearLayout|ancAdjustText              （ancAdapterText/ancLowText/…）
 *       │         ├ LinearLayout|transparentAdjust          (GONE)
 *       │         └ LinearLayout|transparentAdjustText      (GONE)
 *       └ LinearLayout|groupRenameCard
 *
 * 修复前的行为（旧日志原文）：
 *   `native ANC row by view class: … row=android.widget.LinearLayout entry=ancAdjust`
 *   `installed three-mode ANC ui into … entry=anclayout index=2`
 *   → 只藏了滑杆行 `ancAdjust`，兄弟子树 `ancLayoutInfo`（框架的三档按钮）仍然可见
 *     ⇒ 页面同时出现两套控件 = 用户说的「会多出新的按钮」。
 * 另一个问题：entry 名表里只有 `headset_anc*` / `anc_layout`（下划线），真机名是驼峰
 *   `anclayout` / `ancLayoutInfo` / `ancAdjust` ⇒ 名字档一个都没命中（心跳 matched= 为空），
 *   一直靠「类名兜底」工作。现在这两点都按实测修好。
 *
 * ── 定位与替换（四档，先高置信后低置信）──────────────────────────────────────
 *   (a) entry 名（RomProfile.nativeAncTokens）——高置信；
 *   (b) ANC 控件类名（RomProfile.nativeAncViewClasses，H4 实证 MiuiHeadsetAncAdjustView）——高置信；
 *   (c) 滑杆 max ∈ 2..5（4 段 ANC 滑杆的语义特征）——**高置信**（本 ROM 上就是框架 ANC 滑杆）；
 *       同族但非 ANC 的控件（MiuiHeadsetTransparentAdjustView / 名字含 transparent）一律排除，
 *       免得把通透档滑杆误当 ANC 藏掉；
 *   (d) 任意滑杆 —— 低置信，**只插入不隐藏**（怕误藏页面上别的控件）。
 *   (a)(b)(c) 命中后统一「整块替换」：沿父链抬到 RomProfile.nativeAncBlockTokens() 命中的容器
 *   （H4 = `anclayout`），把它整块 GONE，并**把我们的控件插进它的父容器同一位置**（ancCard），
 *   卡片外观（背景/内边距）保持不变。抬不到容器时退化为「藏命中行 + 插在它后面」。
 *
 * 结构（与 App 内 UI 用语保持一致）：
 *   顶层三档   通透 / 降噪 / 关闭          → UI 下标 2 / 1(组) / 0
 *   降噪子三档 自适应 / 抗风 / 普通        → UI 下标 4 / 3 / 1
 *   UI 下标 = core.MoondropModels.AncMode 的声明顺序：
 *     0 OFF / 1 NOISE_CANCELLATION / 2 TRANSPARENCY / 3 ANTI_WIND / 4 ADAPTIVE / 5 LIVE
 *
 * ⚠ 只做展示与点击转发：点击回调由 SettingsHeadsetHook 负责广播 ANC_SELECT（setPackage 到应用进程）。
 * ⚠ 所有视图引用都是弱引用 + 全程 runCatching：页面销毁、厂商改布局都只是「这条路径失效」。
 */
package moe.chenxy.hyperpods.hook

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

object NativeThreeModeAncUi {
    private const val TAG = "HyperPods-AncUi"

    // UI 下标（core.MoondropModels.AncMode 声明顺序）
    private const val UI_OFF = 0
    private const val UI_NOISE_CANCELLATION = 1
    private const val UI_TRANSPARENCY = 2
    private const val UI_ANTI_WIND = 3
    private const val UI_ADAPTIVE = 4

    private val TOP_LABELS = arrayOf("通透", "降噪", "关闭")
    private val TOP_UI = intArrayOf(UI_TRANSPARENCY, UI_NOISE_CANCELLATION, UI_OFF)
    private val SUB_LABELS = arrayOf("自适应", "抗风", "普通")
    private val SUB_UI = intArrayOf(UI_ADAPTIVE, UI_ANTI_WIND, UI_NOISE_CANCELLATION)

    // 用 val 而不是 const：需要 .toInt() 把 0xFF… 收成有符号 Int，const 不允许这种表达式
    /** 视图树 dump 上限（够看清耳机页结构，又不会刷爆 logcat）。 */
    private const val VIEW_DUMP_LIMIT = 80

    private val COLOR_SELECTED = 0xFF007AFF.toInt()
    private val COLOR_SELECTED_BG = 0x1F007AFF
    private val COLOR_NORMAL = 0xFF4C4C4C.toInt()
    private val COLOR_NORMAL_BG = 0x14000000

    // ── 进程内状态（全部弱引用，热重载 / 页面销毁不留引用） ─────────────────────
    private var rootRef: WeakReference<View>? = null
    private var containerRef: WeakReference<LinearLayout>? = null

    /** 被我们整块藏掉的原生 ANC 视图（H4 = LinearLayout|anclayout；无容器时 = 命中的那一行）。 */
    private var hiddenRef: WeakReference<View>? = null

    /** 命中的锚点（类名 / 滑杆），仅用于日志与心跳。 */
    private var anchorRef: WeakReference<View>? = null

    private var topButtons: List<TextView> = emptyList()
    private var subRow: LinearLayout? = null
    private var subButtons: List<TextView> = emptyList()

    private var selectedUi = UI_OFF

    /** 降噪组内最后一次选的子档（默认普通降噪）。 */
    private var lastNoiseSub = UI_NOISE_CANCELLATION

    private var selectCallback: ((Int) -> Unit)? = null

    /** true = 只靠低置信结构猜出来的行（**不隐藏**，只插入）。 */
    private var nativeRowGuessed = false

    /** 决策档位（日志取证）：entryName / viewClass / seekBarMax / anySeekBar / none。 */
    private var nativeRowReason = "none"

    /** 决策细节（entry 名 / 命中的类名 / 滑杆 max）。 */
    private var nativeRowEvidence = "none"

    /** 是否整块替换（隐藏了原生 ANC 容器或其行）。 */
    private var nativeReplaced = false

    /** 整棵视图树每个 root 只 dump 一次（这是「真实的资源 entry 名」唯一可靠的来源）。 */
    private var dumpedRoot: WeakReference<View>? = null

    /** 4 段 ANC 滑杆的 max 通常就是 3（下标 0..3）。放宽到 2..5 覆盖各种档位模板。 */
    private val ANC_SEEK_MAX_RANGE = 2..5

    /** 命名命中的全部候选（供 logcat 取证）。 */
    private val matchedEntryNames = LinkedHashSet<String>()

    /** 一次定位的结论。 */
    private class NativeBlock(
        /** 触发命中的那个视图（类名 / 滑杆）。 */
        val anchor: View,
        /** 真要隐藏的视图：优先整块容器，抬不到容器时是抬到行的锚点。 */
        val target: View,
        /** 整块容器（`anclayout` 之类）；null = 只找到一行。 */
        val block: View?,
        /** 决策档位。 */
        val reason: String,
        /** 决策细节。 */
        val evidence: String,
        /** true = (a)(b)(c) 档，可以隐藏；false = 只插入不隐藏。 */
        val highConfidence: Boolean
    )

    /**
     * 在原页面里安装（或复用）我们的三档控件。
     * @return true = 控件已就位（新建或复用）。
     */
    fun install(fragment: Any?, currentUi: Int, onSelect: (Int) -> Unit): Boolean {
        selectCallback = onSelect
        val root = fragmentRoot(fragment) ?: return false
        rootRef = WeakReference(root)
        selectedUi = currentUi
        if (isNoiseGroup(currentUi)) lastNoiseSub = currentUi

        if (dumpedRoot?.get() !== root) {
            dumpedRoot = WeakReference(root)
            dumpViewTree(root)
        }

        containerRef?.get()?.let { alive ->
            if (alive.parent != null) {
                refresh(currentUi)
                reassert()
                return true
            }
        }

        return runCatching { buildAndInsert(root) }
            .onFailure { Log.w(TAG, "install three-mode ANC ui failed", it) }
            .getOrDefault(false)
    }

    /** 跟随模块状态刷新选中项（不重建视图）。 */
    fun refresh(currentUi: Int) {
        selectedUi = currentUi
        if (isNoiseGroup(currentUi)) lastNoiseSub = currentUi
        runCatching { paint() }.onFailure { Log.w(TAG, "refresh three-mode ANC ui failed", it) }
    }

    /**
     * 页面每次 attach / 布局后重新声明一次：厂商会把我们的控件藏掉或把原生行放回来。
     * 由 SettingsHeadsetHook 的 3s 周期任务与 onCreateView 后调用。
     */
    fun reassert() {
        val container = containerRef?.get() ?: return
        if (container.parent == null) return
        runCatching {
            if (container.visibility != View.VISIBLE) {
                container.visibility = View.VISIBLE
                Log.d(TAG, "reasserted visibility of three-mode ANC ui")
            }
            container.bringToFront()
            // 厂商会重建原生控件（换设备 / 断线重连 / 它的周期刷新）：每次重新定位一次，
            // 藏掉**当前**那一份，而不是死抱着安装时记下的引用 —— 否则新控件会重新露出来，
            // 页面上又会同时出现两套（就是用户报的「多出新的按钮」）。
            val root = rootRef?.get()
            if (root != null) {
                val found = findNativeAncBlock(root)
                val high = found?.takeIf { it.highConfidence }
                val target: View? = high?.block ?: high?.target
                if (target != null && target !== container) {
                    hiddenRef = WeakReference(target)
                    nativeRowReason = found?.reason ?: nativeRowReason
                    nativeRowEvidence = found?.evidence ?: nativeRowEvidence
                    nativeReplaced = true
                    nativeRowGuessed = false
                    if (target.visibility != View.GONE) {
                        target.visibility = View.GONE
                        Log.d(TAG, "re-hid native ANC ($nativeRowReason) entry=${entryName(target)}")
                    }
                } else {
                    // 没定位到（可能页面已经切成别的片段）：把上次藏掉的那份保持 GONE。
                    val stale = hiddenRef?.get()
                    if (stale != null && stale !== container && stale.visibility != View.GONE) {
                        stale.visibility = View.GONE
                    }
                }
            }
        }.onFailure { Log.w(TAG, "reassert three-mode ANC ui failed", it) }
    }

    fun reset() {
        rootRef = null
        containerRef = null
        hiddenRef = null
        anchorRef = null
        topButtons = emptyList()
        subRow = null
        subButtons = emptyList()
        selectCallback = null
        nativeRowGuessed = false
        nativeRowReason = "none"
        nativeRowEvidence = "none"
        nativeReplaced = false
        dumpedRoot = null
        matchedEntryNames.clear()
        selectedUi = UI_OFF
        lastNoiseSub = UI_NOISE_CANCELLATION
    }

    /**
     * 周期心跳用：一眼看出 ANC 控件到底装上了没有、原生控件藏掉没有、靠什么证据找到的。
     * 按用户要求明确给出 `nativeHidden=true/false reason=…`。
     */
    fun stateSummary(): String =
        "attached=" + isAttached() +
            " nativeHidden=" + nativeHidden() +
            " reason=" + nativeRowReason +
            " evidence=" + nativeRowEvidence +
            " guess=" + nativeRowGuessed +
            " replaced=" + nativeReplaced +
            " matched=" + matchedEntryNames.joinToString()

    /** 原生 ANC 控件是否已经被我们藏掉。 */
    private fun nativeHidden(): Boolean {
        val hidden = hiddenRef?.get() ?: return false
        return hidden.visibility == View.GONE
    }

    /** 取证用：命名命中的 ANC 资源 entry 名。 */
    fun matchedNames(): List<String> = matchedEntryNames.toList()

    /** 取证用：是否只靠结构猜出原生 ANC 行。 */
    fun usedStructuralGuess(): Boolean = nativeRowGuessed

    /** 控件是否还在页面上（周期任务用它决定要不要重装）。 */
    fun isAttached(): Boolean = containerRef?.get()?.parent != null

    // ── 组装 ─────────────────────────────────────────────────────────────────

    private fun buildAndInsert(root: View): Boolean {
        val context = root.context ?: return false
        val found = findNativeAncBlock(root)
        anchorRef = found?.let { WeakReference(it.anchor) }
        nativeRowGuessed = found != null && !found.highConfidence
        nativeRowReason = found?.reason ?: "none"
        nativeRowEvidence = found?.evidence ?: "none"

        val container = buildControl(context) ?: return false

        // 全部落成局部 val，避免依赖属性智能转换（CI 是唯一的编译判据）。
        val highFound = found?.takeIf { it.highConfidence }
        val foundBlock: View? = highFound?.block
        val foundTarget: View? = highFound?.target
        val blockParent: ViewGroup? = foundBlock?.parent as? ViewGroup
        val targetParent: ViewGroup? = foundTarget?.parent as? ViewGroup

        val insertParent: ViewGroup
        val insertIndex: Int
        val hideTarget: View?
        if (foundBlock != null && blockParent != null) {
            // (a)(b)(c) + 找到整块容器：整块替换（藏容器，插到它的父容器同一位置）
            insertParent = blockParent
            insertIndex = blockParent.indexOfChild(foundBlock) + 1
            hideTarget = foundBlock
        } else if (foundTarget != null && targetParent != null) {
            // (a)(b)(c) 但抬不到容器：藏命中行，插在它后面
            insertParent = targetParent
            insertIndex = targetParent.indexOfChild(foundTarget) + 1
            hideTarget = foundTarget
        } else {
            // (d) 低置信：只插入不隐藏
            val host = findHostContainer(root)
            if (host == null) {
                Log.w(TAG, "no injectable container found; ANC ui not installed")
                return false
            }
            insertParent = host
            insertIndex = host.childCount
            hideTarget = null
            Log.w(
                TAG,
                "native ANC row NOT located (or only low-confidence); framework control stays visible. " +
                    "Injecting our own row into ${host.javaClass.name} entry=${entryName(host)} " +
                    "(see the 'tree ' lines above for the real entry names)"
            )
        }

        insertParent.addView(container, insertIndex.coerceIn(0, insertParent.childCount))
        containerRef = WeakReference(container)
        hiddenRef = hideTarget?.let { WeakReference(it) }
        nativeReplaced = hideTarget != null

        // 先藏再 post 一次：厂商会在第一帧把自己的控件放回来。
        hideTarget?.let { it.visibility = View.GONE }
        container.post { reassert() }

        Log.i(
            TAG,
            "installed three-mode ANC ui into ${insertParent.javaClass.name} " +
                "entry=${entryName(insertParent)} index=$insertIndex " +
                "nativeHidden=${hideTarget != null} reason=$nativeRowReason evidence=$nativeRowEvidence " +
                "hiddenEntry=${entryName(hideTarget)} matched=${matchedEntryNames.joinToString()}"
        )
        return true
    }

    private fun buildControl(context: Context): LinearLayout? {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16f), dp(context, 8f), dp(context, 16f), dp(context, 12f))
        }

        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<TextView>(TOP_LABELS.size)
        TOP_LABELS.forEachIndexed { i, label ->
            val tv = segment(context, label) { onTopClicked(i) }
            top.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 8f)
            })
            buttons.add(tv)
        }
        container.addView(top)
        topButtons = buttons

        val sub = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val subs = ArrayList<TextView>(SUB_LABELS.size)
        SUB_LABELS.forEachIndexed { i, label ->
            val tv = segment(context, label) { onSubClicked(i) }
            sub.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 8f)
                topMargin = dp(context, 6f)
            })
            subs.add(tv)
        }
        container.addView(sub)
        subRow = sub
        subButtons = subs

        paint()
        return container
    }

    private fun segment(context: Context, label: String, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = dp(context, 40f)
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
        }

    private fun paint() {
        val topIndex = TOP_UI.indexOfFirst { it == selectedUi }
            .let { if (it >= 0) it else TOP_UI.indexOf(UI_NOISE_CANCELLATION) }
        topButtons.forEachIndexed { i, tv -> style(ctxOf(tv), tv, i == topIndex) }
        val inNoise = isNoiseGroup(selectedUi)
        subRow?.visibility = if (inNoise) View.VISIBLE else View.GONE
        subButtons.forEachIndexed { i, tv -> style(ctxOf(tv), tv, inNoise && SUB_UI[i] == selectedUi) }
    }

    private fun ctxOf(view: View): Context = view.context

    private fun style(context: Context, view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) COLOR_SELECTED else COLOR_NORMAL)
        view.background = pill(context, if (selected) COLOR_SELECTED_BG else COLOR_NORMAL_BG)
    }

    private fun pill(context: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, 20f).toFloat()
        setColor(color)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // ── 点击 ─────────────────────────────────────────────────────────────────

    private fun onTopClicked(index: Int) {
        when (index) {
            0 -> select(UI_TRANSPARENCY, expandNoise = false)
            2 -> select(UI_OFF, expandNoise = false)
            else -> select(lastNoiseSub, expandNoise = true)
        }
    }

    private fun onSubClicked(index: Int) {
        val ui = SUB_UI.getOrNull(index) ?: return
        lastNoiseSub = ui
        select(ui, expandNoise = true)
    }

    private fun select(ui: Int, expandNoise: Boolean) {
        selectedUi = ui
        runCatching { paint() }.onFailure { Log.w(TAG, "paint after select failed", it) }
        if (!expandNoise) subRow?.visibility = View.GONE
        Log.i(TAG, "three-mode ANC selected uiIndex=$ui")
        runCatching { selectCallback?.invoke(ui) }
            .onFailure { Log.w(TAG, "ANC select callback failed uiIndex=$ui", it) }
    }

    private fun isNoiseGroup(ui: Int): Boolean =
        ui == UI_NOISE_CANCELLATION || ui == UI_ANTI_WIND || ui == UI_ADAPTIVE

    // ── 视图查找 ─────────────────────────────────────────────────────────────

    private fun fragmentRoot(fragment: Any?): View? =
        runCatching { callMethod(fragment, "getView") as? View }.getOrNull()

    /**
     * 四档定位（见文件头）：(a) entry 名 → (b) 控件类名 → (c) 滑杆 max∈2..5 → (d) 任意滑杆。
     * (a)(b)(c) 都是高置信（可隐藏并替换），(d) 低置信（只插入）。
     */
    private fun findNativeAncBlock(root: View): NativeBlock? {
        val excludeClasses = RomProfile.nativeAncExcludeViewClasses
        findByName(root)?.let { match ->
            val reason = "entryName"
            val evidence = entryName(match) ?: "-"
            Log.i(TAG, "native ANC anchor by $reason: entry=$evidence class=${match.javaClass.name}")
            return blockOf(match, reason, evidence, high = true)
        }
        findByClassName(root, RomProfile.nativeAncViewClasses, excludeClasses)?.let { (match, matchedClass) ->
            val reason = "viewClass"
            val evidence = matchedClass.substringAfterLast('.')
            Log.i(TAG, "native ANC anchor by $reason: class=$matchedClass entry=${entryName(match)}")
            return blockOf(match, reason, evidence, high = true)
        }
        findBySeekBarSignature(root, excludeClasses)?.let { (match, max) ->
            val reason = "seekBarMax"
            Log.i(
                TAG,
                "native ANC anchor by $reason: class=${match.javaClass.name} " +
                    "entry=${entryName(match)} max=$max"
            )
            return blockOf(match, reason, max.toString(), high = true)
        }
        val any = findBySeekBar(root, excludeClasses)
        nativeRowGuessed = any != null
        if (any != null) {
            Log.w(
                TAG,
                "ANC anchor resolved only as 'some seek bar' (low confidence); it will NOT be hidden " +
                    "(class=${any.javaClass.name} entry=${entryName(any)})"
            )
            return NativeBlock(any, normaliseRow(any), null, "anySeekBar", "anySeekBar", highConfidence = false)
        }
        return null
    }

    private fun blockOf(match: View, reason: String, evidence: String, high: Boolean): NativeBlock {
        val block = findBlockAncestor(match)
        val target = block ?: normaliseRow(match)
        if (block != null) {
            Log.i(
                TAG,
                "native ANC block container found: entry=${entryName(block)} " +
                    "class=${block.javaClass.name} (will be hidden and replaced)"
            )
        } else {
            Log.i(TAG, "native ANC block container NOT found; hiding the matched row instead")
        }
        return NativeBlock(match, target, block, reason, evidence, high)
    }

    /** 沿父链（含自身）找「原生 ANC 控件整块」的容器名，见 RomProfile.nativeAncBlockTokens()。 */
    private fun findBlockAncestor(view: View): View? {
        var node: View? = view
        while (node != null) {
            val name = entryName(node)
            if (!name.isNullOrEmpty() && RomProfile.isNativeAncBlockEntry(name)) return node
            node = node.parent as? View
        }
        return null
    }

    /**
     * 名字匹配分两档（两张表都由 RomProfile 按检测到的 ROM 代数给出）：
     *   1) 主档 = 本代 ROM 的 entry 名候选（HyperOS 4 = 真机 dump + resources.arsc 实证）；
     *   2) 兜底档 = 其余代数的候选，**只有主档一个都没命中时**才用（保持旧代码的候选容忍度）。
     */
    private fun findByName(root: View): View? {
        val (primary, fallback) = RomProfile.nativeAncTokens()
        findByName(root, primary, "primary")?.let { return it }
        return findByName(root, fallback, "fallback")
    }

    /**
     * 类名定位（次高置信）：RomProfile 给出「原生 ANC 控件类」候选（本代优先）。
     * HyperOS 4 上是 com.android.settings.bluetooth.MiuiHeadsetAncAdjustView —— 它内部就是
     * AncLevelChangeListener / LabeledSeekBarExploreByTouchHelper，比「随便一个滑杆」可靠。
     * 同族的 MiuiHeadsetTransparentAdjustView（通透档）在 excludeClasses 里，必须跳过。
     */
    private fun findByClassName(
        root: View,
        classNames: List<String>,
        excludeClasses: List<String>
    ): Pair<View, String>? {
        if (classNames.isEmpty()) return null
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            val cls = view.javaClass
            val matched = classNames.firstOrNull { it == cls.name || it == cls.simpleName }
            if (matched != null && !isExcluded(view, excludeClasses)) {
                return view to cls.name
            }
            if (view is ViewGroup) for (c in 0 until view.childCount) queue.add(view.getChildAt(c))
        }
        return null
    }

    private fun findByName(root: View, tokens: List<AncToken>, tier: String): View? {
        if (tokens.isEmpty()) return null
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            val name = entryName(view)
            if (name != null && RomProfile.matchesNativeAncEntry(name, tokens)) {
                matchedEntryNames.add("${name}(${view.javaClass.simpleName},$tier)")
                Log.d(TAG, "ANC candidate entry=$name tier=$tier class=${view.javaClass.name}")
                return view
            }
            if (view is ViewGroup) for (c in 0 until view.childCount) queue.add(view.getChildAt(c))
        }
        return null
    }

    /**
     * 与名字无关的语义特征：滑杆 max ∈ 2..5，基本就是 4 段 ANC 滑杆。
     * **本 ROM 上这条已升格为高置信**（会隐藏并替换）；同族的通透档滑杆被排除。
     * 返回 (锚点, max)。
     */
    private fun findBySeekBarSignature(root: View, excludeClasses: List<String>): Pair<View, Int>? {
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            if (isSeekBar(view)) {
                val max = runCatching { callMethod(view, "getMax") as? Int }.getOrNull() ?: -1
                val excluded = isExcluded(view, excludeClasses)
                Log.i(
                    TAG,
                    "seekBar candidate entry=${entryName(view)} class=${view.javaClass.name} " +
                        "max=$max excluded=$excluded"
                )
                if (max in ANC_SEEK_MAX_RANGE && !excluded) return view to max
            }
            if (view is ViewGroup) for (c in 0 until view.childCount) queue.add(view.getChildAt(c))
        }
        return null
    }

    /** 最后兜底：任意滑杆（不判 max），同样排除通透档滑杆。 */
    private fun findBySeekBar(root: View, excludeClasses: List<String>): View? {
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            if (isSeekBar(view) && !isExcluded(view, excludeClasses)) return view
            if (view is ViewGroup) for (c in 0 until view.childCount) queue.add(view.getChildAt(c))
        }
        return null
    }

    /**
     * 候选是否属于「同族但不是 ANC」的控件：自身或任一祖先命中排除类名，
     * 或 entry 名里含 transparent。通透档滑杆与 ANC 档滑杆是同一个 AbsSeekBar 模板，
     * 不排除就会把页面上另一半控件误藏。
     */
    private fun isExcluded(view: View, excludeClasses: List<String>): Boolean {
        var node: View? = view
        var depth = 0
        while (node != null && depth < 8) {
            val cls = node.javaClass
            if (excludeClasses.any { it == cls.name || it == cls.simpleName }) return true
            val name = entryName(node)
            if (!name.isNullOrEmpty() && name.lowercase().contains("transparent")) return true
            node = node.parent as? View
            depth++
        }
        return false
    }

    /**
     * 把 fragment 的整棵视图树打进 logcat（每个 root 一次）。
     * 这是「本 ROM 上真实的资源 entry 名到底是什么」唯一可靠的来源——
     * 静态反编译 resources.arsc 拿不到布局层级，只能实机量。
     */
    private fun dumpViewTree(root: View) {
        runCatching {
            val lines = ArrayList<String>()
            val queue = ArrayList<View>()
            queue.add(root)
            var i = 0
            while (i < queue.size && lines.size < VIEW_DUMP_LIMIT) {
                val v = queue[i++]
                val kids = if (v is ViewGroup) v.childCount else 0
                lines.add(
                    v.javaClass.simpleName + "|" + (entryName(v) ?: "-") +
                        "|id=0x" + v.id.toString(16) + "|kids=" + kids + "|vis=" + v.visibility
                )
                if (v is ViewGroup) for (c in 0 until v.childCount) queue.add(v.getChildAt(c))
            }
            Log.i(TAG, "fragment view tree dump: ${lines.size} nodes")
            lines.forEach { Log.i(TAG, "  tree $it") }
        }.onFailure { Log.w(TAG, "view tree dump failed", it) }
    }

    /** 命中的可能是叶子（比如标签 Text 或滑杆 View），尽量抬到它所在的「行」容器。 */
    private fun normaliseRow(view: View): View {
        if (view is ViewGroup) return view
        val parent = view.parent as? ViewGroup ?: return view
        val grand = parent.parent as? ViewGroup ?: return parent
        // 父容器只有少数几个孩子时才认为它就是那一行，否则用祖父层
        return if (parent.childCount <= 4) parent else grand
    }

    private fun isSeekBar(view: View): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null) {
            when (cls.name) {
                "android.widget.AbsSeekBar", "android.widget.SeekBar" -> return true
            }
            cls = cls.superclass
        }
        return false
    }

    /** 注入兜底容器：第一个「竖向 LinearLayout 且有孩子」的容器，再退化到 root。 */
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

    // 资源 entry 名的正负向匹配规则已全部搬进 RomProfile（nativeAncTokens / nativeAncBlockTokens /
    // matchesNativeAncEntry / isNativeAncBlockEntry），本文件不再硬编码名字族：
    //   · HyperOS 4（VERIFIED_H4_RES = 真机 view-tree dump + resources.arsc）：
    //     控件名 anclayout / ancLayoutInfo / ancAdjust / ancAdjustText / ancAdjustView /
    //     openAnc / closeAnc / transparentAdjust；命名族 headset_anc* / miheadset_anc* / anc_level*
    //   · HyperOS 4 排除类：MiuiHeadsetTransparentAdjustView（通透档滑杆）
    //   · HyperOS 3（unverified-on-device）：同一命名族 + 旧 MiLink 面板的 audio_effect_view /
    //     mi_audio_ringing_view（OppoPods 在旧 ROM 上用的 entry 名）
}
