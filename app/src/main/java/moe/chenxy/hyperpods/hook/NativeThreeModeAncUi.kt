/*
 * HyperPods for Moondrop — 在 HyperOS 原生耳机页里装一套「三档降噪」控件
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 需求（用户）：「PuddingPods 把降噪设置适配进系统界面了，仿照一下」。
 * 参考实现（PuddingPods 构建，DEX 里能看到 `SettingsHeadsetHook$PuddingAncUi` /
 * `installPuddingThreeModeUi` / `PuddingAncUi(controlRow=…)`）做的事情是：
 *   在 com.android.settings 的原生 MiuiHeadsetFragment 里**隐藏框架自己的 ANC 控件行**，
 *   并在同一位置放一套自己的三档控件，点击后走模块自己的 ANC 通道。
 *
 * ⚠ 与参考实现的取证差异（重要）：
 *   OppoPods 的 `findViewByEntryName("audio_effect_view" / "mi_audio_ringing_view" / …)` 用在
 *   **com.milink.service 的 MiLink 面板**（com.miui.circulate*），不是设置页。设置页的 ANC 控件
 *   是另一套资源名，本 ROM 实测存在的是（从 com.android.settings 的 resources.arsc 里抓到的
 *   真实条目名，MIUI 习惯用视图 id 给 dimen 命名）：
 *     headset_anc_layout_width / headset_anc_layout_hight / headset_anc_layout_marginTop
 *     headset_anc_level_Text_height / headset_anc_level_layout_height
 *     headset_anc_mode_text_size / headset_anc_text_size / headset_anc_image_high
 *     miheadset_ancClosed / miheadset_ancMild / miheadset_ancDepth / miheadset_anc_indicate …
 *     res/drawable/openanc_on.xml, openanc_off.xml, closeanc_on.xml, closeanc_off.xml
 *     res/layout/headsetlayout.xml
 *   ⇒ 视图 id 极可能就叫 headset_anc_layout / headset_anc_level_layout / headset_anc_level_Text 等。
 *   本模块**不硬编码**这些名字，而是在运行时用 `View.resources.getResourceEntryName(view.id)`
 *   去匹配（release 构建会重命名资源 entry，硬编码 id 更不可靠），并且**必须先命中名字**才隐藏
 *   原生控件；只靠结构猜出来的行不隐藏，避免把页面上别的控件误藏。
 *   ⚠ 名字族本身现在由 RomProfile 按检测到的 ROM 代数给出（HyperOS 4 = resources.arsc 实证；
 *     HyperOS 3 = 旧参考实现的命名 + 旧 MiLink 面板 entry 名，unverified-on-device）。
 *   （本机无法完整反编译 resources.arsc 的二进制 XML，所以没有静态确认布局层级——
 *     若运行时一个 headset_anc* 名字都没命中，会退化为「保留原生控件 + 在旁边插入我们自己的一行」，
 *     并把找到的候选 entry 名全部打进 logcat，供下一轮定位。）
 *
 * 结构（与 App 内 UI 用语保持一致）：
 *   顶层三档   通透 / 降噪 / 关闭          → UI 下标 2 / 1(组) / 0
 *   降噪子三档 自适应 / 抗风 / 普通        → UI 下标 4 / 3 / 1
 *   UI 下标 = core.MoondropModels.AncMode 的声明顺序（与 SettingsHeadsetHook 既有映射一致）：
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
    private var nativeRowRef: WeakReference<View>? = null
    private var topButtons: List<TextView> = emptyList()
    private var subRow: LinearLayout? = null
    private var subButtons: List<TextView> = emptyList()

    private var selectedUi = UI_OFF

    /** 降噪组内最后一次选的子档（默认普通降噪）。 */
    private var lastNoiseSub = UI_NOISE_CANCELLATION

    private var selectCallback: ((Int) -> Unit)? = null

    /** 原生 ANC 行是否只靠结构猜出来（= true 时我们**不隐藏**它）。 */
    private var nativeRowGuessed = false

    /** 原生 ANC 行是怎么找到的（日志取证：entryName / seekBarMax=N / anySeekBar / none）。 */
    private var nativeRowEvidence = "none"

    /** 整棵视图树每个 root 只 dump 一次（这是「真实的资源 entry 名」唯一可靠的来源）。 */
    private var dumpedRoot: WeakReference<View>? = null

    /** 4 段 ANC 滑杆的 max 通常就是 3（下标 0..3）。放宽到 2..5 覆盖各种档位模板。 */
    private val ANC_SEEK_MAX_RANGE = 2..5

    /** 命名命中的全部候选（供 logcat 取证）。 */
    private val matchedEntryNames = LinkedHashSet<String>()

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
            if (!nativeRowGuessed) {
                nativeRowRef?.get()?.let { row ->
                    if (row !== container && row.visibility != View.GONE) {
                        row.visibility = View.GONE
                        Log.d(TAG, "re-hid native ANC row")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "reassert three-mode ANC ui failed", it) }
    }

    fun reset() {
        rootRef = null
        containerRef = null
        nativeRowRef = null
        topButtons = emptyList()
        subRow = null
        subButtons = emptyList()
        selectCallback = null
        nativeRowGuessed = false
        nativeRowEvidence = "none"
        dumpedRoot = null
        matchedEntryNames.clear()
        selectedUi = UI_OFF
        lastNoiseSub = UI_NOISE_CANCELLATION
    }

    /** 周期心跳用：一眼看出 ANC 控件到底装上了没有、原生行藏掉没有、靠什么证据找到的。 */
    fun stateSummary(): String =
        "attached=" + isAttached() +
            " nativeHidden=" + (nativeRowRef?.get()?.visibility == View.GONE) +
            " evidence=" + nativeRowEvidence +
            " guess=" + nativeRowGuessed +
            " matched=" + matchedEntryNames.joinToString()

    /** 取证用：命名命中的 ANC 资源 entry 名。 */
    fun matchedNames(): List<String> = matchedEntryNames.toList()

    /** 取证用：是否只靠结构猜出原生 ANC 行。 */
    fun usedStructuralGuess(): Boolean = nativeRowGuessed

    /** 控件是否还在页面上（周期任务用它决定要不要重装）。 */
    fun isAttached(): Boolean = containerRef?.get()?.parent != null

    // ── 组装 ─────────────────────────────────────────────────────────────────

    private fun buildAndInsert(root: View): Boolean {
        val context = root.context ?: return false
        val native = findNativeAncRow(root)
        nativeRowRef = native?.let { WeakReference(it) }

        val container = buildControl(context) ?: return false

        val parent: ViewGroup
        val index: Int
        val nativeParent = native?.parent as? ViewGroup
        if (nativeParent != null) {
            parent = nativeParent
            index = nativeParent.indexOfChild(native) + 1
        } else {
            val host = findHostContainer(root) ?: return false
            parent = host
            index = host.childCount
            Log.w(
                TAG,
                "native ANC row NOT located; framework control stays visible. " +
                    "Injecting our own row into ${host.javaClass.name} entry=${entryName(host)} " +
                    "(see the 'tree ' lines above for the real entry names)"
            )
        }

        parent.addView(container, index.coerceIn(0, parent.childCount))
        containerRef = WeakReference(container)
        // 布局完成后再声明一次：厂商会在第一帧把自己的控件放回来。
        container.post { reassert() }

        if (native != null && !nativeRowGuessed) {
            native.visibility = View.GONE
            Log.i(
                TAG,
                "native ANC row hidden entry=${entryName(native)} class=${native.javaClass.name} " +
                    "matched=${matchedEntryNames.joinToString()}"
            )
        } else if (native != null) {
            Log.w(
                TAG,
                "native ANC row kept visible (low-confidence evidence=${nativeRowEvidence}); " +
                    "see the 'tree ' lines for a reliable entry name"
            )
        }
        Log.i(
            TAG,
            "installed three-mode ANC ui into ${parent.javaClass.name} entry=${entryName(parent)} " +
                "index=$index evidence=$nativeRowEvidence matched=${matchedEntryNames.joinToString()}"
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
     * 四级定位，先高置信后低置信：
     *   1) 资源 entry 名（headset_anc* 等）—— 高置信，隐藏原生行；
     *   2) 原生 ANC 控件的**类名**（RomProfile.NATIVE_ANC_VIEW；HyperOS 4 实证
     *      `com.android.settings.bluetooth.MiuiHeadsetAncAdjustView`）—— 同样高置信，隐藏原生行；
     *   3) 滑杆 max ∈ 2..5 —— 4 段 ANC 滑杆的语义特征，与名字无关，同样够格隐藏；
     *   4) 任意滑杆 —— 低置信，**只插入不隐藏**（怕误藏页面上别的控件）。
     */
    private fun findNativeAncRow(root: View): View? {
        findByName(root)?.let {
            nativeRowGuessed = false
            nativeRowEvidence = "entryName:" + entryName(it)
            Log.i(TAG, "native ANC row by entry name: ${nativeRowEvidence}")
            return normaliseRow(it)
        }
        findByClassName(root, RomProfile.nativeAncViewClasses)?.let { (row, matchedClass) ->
            nativeRowGuessed = false
            nativeRowEvidence = "viewClass:" + matchedClass.substringAfterLast('.')
            Log.i(
                TAG,
                "native ANC row by view class: class=$matchedClass row=${row.javaClass.name} " +
                    "entry=${entryName(row)}"
            )
            return row
        }
        findBySeekBarSignature(root)?.let { row ->
            nativeRowGuessed = false
            nativeRowEvidence = "seekBarMax"
            Log.i(
                TAG,
                "native ANC row by seekBar signature class=${row.first.javaClass.name} " +
                    "entry=${entryName(row.first)}"
            )
            return row.first
        }
        val any = findBySeekBar(root)
        nativeRowGuessed = any != null
        nativeRowEvidence = if (any != null) "anySeekBar" else "none"
        if (any != null) {
            Log.w(TAG, "ANC row resolved only as 'some seek bar' (low confidence); it will NOT be hidden")
        }
        return any
    }

    /**
     * 名字匹配分两档（两张表都由 RomProfile 按检测到的 ROM 代数给出）：
     *   1) 主档 = 本代 ROM 的 entry 名候选（HyperOS 4 是 resources.arsc 里核对过的 headset_anc* / miheadset_anc*）；
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
     * 返回 (抬到行的 view, 命中的类名)。
     */
    private fun findByClassName(root: View, classNames: List<String>): Pair<View, String>? {
        if (classNames.isEmpty()) return null
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            val cls = view.javaClass
            val matched = classNames.firstOrNull { it == cls.name || it == cls.simpleName }
            if (matched != null) return normaliseRow(view) to cls.name
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

    /** 与名字无关的语义特征：滑杆 max ∈ 2..5，基本就是 4 段 ANC 滑杆。返回 (行, max)。 */
    private fun findBySeekBarSignature(root: View): Pair<View, Int>? {
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            if (isSeekBar(view)) {
                val max = runCatching { callMethod(view, "getMax") as? Int }.getOrNull() ?: -1
                Log.i(TAG, "seekBar candidate entry=${entryName(view)} class=${view.javaClass.name} max=$max")
                if (max in ANC_SEEK_MAX_RANGE) return normaliseRow(view) to max
            }
            if (view is ViewGroup) for (c in 0 until view.childCount) queue.add(view.getChildAt(c))
        }
        return null
    }

    /** 最后兜底：任意滑杆（不判 max）。 */
    private fun findBySeekBar(root: View): View? {
        val queue = ArrayList<View>()
        queue.add(root)
        var i = 0
        while (i < queue.size) {
            val view = queue[i++]
            if (isSeekBar(view)) return normaliseRow(view)
            if (view is ViewGroup) for (c in 0 until view.childCount) queue.add(view.getChildAt(c))
        }
        return null
    }

    /**
     * 把 fragment 的整棵视图树打进 logcat（每个 root 一次）。
     * 这是「本 ROM 上真实的资源 entry 名到底是什么」唯一可靠的来源——
     * 静态反编译 resources.arsc 拿不到（release 会重命名），只能实机量。
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

    /** 命中的可能是叶子（比如标签 Text），尽量抬到它所在的「行」容器。 */
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

    private fun entryName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    // 资源 entry 名的正负向匹配规则已全部搬进 RomProfile（nativeAncTokens / matchesNativeAncEntry），
    // 本文件不再硬编码 headset_anc* 这类名字族：
    //   · HyperOS 4（VERIFIED_H4_RES）：headset_anc_layout_* / headset_anc_level_* /
    //     headset_anc_mode_text_size / headset_anc_text_* / headset_anc_image_high / miheadset_anc*
    //     （从本机 com.android.settings 的 resources.arsc 里逐条抓到）
    //   · HyperOS 3（unverified-on-device）：同一命名族 + 旧 MiLink 面板的 audio_effect_view /
    //     mi_audio_ringing_view（OppoPods 在旧 ROM 上用的 entry 名）
    //   · 负向排除 cancel / balance / advanced / enhance / financ 也在 RomProfile 里。
}
