package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.model.AiBgMusic
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiBgMusicSettingsDialog : BaseBottomSheetDialogFragment(0) {

    private val callback: Callback? get() = activity as? Callback
    private var promptNameView: TextView? = null
    private var modelProfileNameView: TextView? = null

    private val selectMusicDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                if (it.isContentScheme()) {
                    kotlin.runCatching {
                        requireContext().contentResolver.takePersistableUriPermission(
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                }
                AiBgMusic.musicDir = it.toString()
                // 刷新对话框内目录输入框
                val dirInput = view?.findViewWithTag<EditText>("bgm_dir_input")
                dirInput?.setText(it.toString())
                dirInput?.setSelection(it.toString().length)
            }
        }
    private data class ModelPreset(
        val provider: String,
        val label: String,
        val baseUrl: String,
        val model: String,
    )

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return buildView()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            isDraggable = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildView(): View {
        val context = requireContext()
        val config = AiBgMusic.config()
        val scrollView = NestedScrollView(context).apply {
            isFillViewport = false
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx())
        }
        scrollView.addView(root)

        // 启用背景音乐开关（使用 Switch 替代 CheckBox）
        fun enableSwitch(): MaterialSwitch {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 8.dpToPx())
            }
            val label = android.widget.TextView(context).apply {
                text = "启用背景音乐"
                textSize = 16f
            }
            val switch = MaterialSwitch(context).apply {
                isChecked = AiBgMusic.enabled
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
            root.addView(row)
            return switch
        }
        val enableBgmCheckBox = enableSwitch()

        // 分隔线
        root.addView(View(context).apply {
            setBackgroundColor(ResourcesCompat.getColor(context.resources, android.R.color.darker_gray, context.theme))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply {
                bottomMargin = 12.dpToPx()
            }
        })

        fun label(text: String) {
            root.addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            })
        }

        fun edit(text: String, hint: String, minLines: Int = 1): EditText {
            return EditText(context).apply {
                setText(text)
                this.hint = hint
                this.minLines = minLines
            }.also(root::addView)
        }

        fun secretEdit(text: String, hint: String): EditText {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val input = EditText(context).apply {
                setText(text)
                this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
                setSingleLine(true)
                setSelection(this.text?.length ?: 0)
            }

            var visible = false
            val toggle = ImageButton(context).apply {
                setImageResource(R.drawable.ic_visibility_off)
                contentDescription = "显示密钥"
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(10.dpToPx())
                setOnClickListener {
                    visible = !visible
                    input.transformationMethod = if (visible) {
                        HideReturnsTransformationMethod.getInstance()
                    } else {
                        PasswordTransformationMethod.getInstance()
                    }
                    setImageResource(if (visible) R.drawable.ic_visibility_on else R.drawable.ic_visibility_off)
                    contentDescription = if (visible) "隐藏密钥" else "显示密钥"
                    input.setSelection(input.text?.length ?: 0)
                }
            }

            row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                toggle,
                LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
            )
            root.addView(row)
            return input
        }

        label("背景音乐文件目录")
        val musicDir = edit(config.musicDir, "选择或粘贴背景音乐文件夹路径 / content:// 目录").apply {
            tag = "bgm_dir_input"
        }
        root.addView(MaterialButton(context).apply {
            text = "选择目录"
            setOnClickListener {
                selectMusicDirLauncher.launch(null)
            }
        })

        val presets = modelPresets()
        var providerName = presets.firstOrNull {
            it.baseUrl == config.modelUrl && it.model == config.modelName
        }?.provider ?: "自定义"
        var providerPresets = presets.filter { it.provider == providerName }

        label("模型厂牌")
        val providerButton = MaterialButton(context)
        root.addView(providerButton)

        label("模型")
        val modelButton = MaterialButton(context)
        root.addView(modelButton)

        label("AI 模型地址")
        val modelUrl = edit(config.modelUrl, "例如 https://api.openai.com/v1 或完整 /v1/chat/completions")

        label("模型名")
        val modelName = edit(config.modelName, "例如 gpt-4o-mini / deepseek-chat")

        fun refreshPresetButtons() {
            providerButton.text = "厂牌：$providerName"
            val matched = presets.firstOrNull {
                it.provider == providerName && it.baseUrl == modelUrl.text?.toString() && it.model == modelName.text?.toString()
            }
            modelButton.text = if (providerName == "自定义") {
                "模型：自定义"
            } else {
                "模型：${matched?.label ?: modelName.text?.toString()?.ifBlank { "请选择" }}"
            }
            modelProfileNameView?.text = "当前密钥配置：${AiBgMusic.selectedModelProfileName.ifBlank { "未选择" }}"
        }

        fun applyPreset(preset: ModelPreset) {
            providerName = preset.provider
            providerPresets = presets.filter { it.provider == providerName }
            modelUrl.setText(preset.baseUrl)
            modelName.setText(preset.model)
            refreshPresetButtons()
        }

        providerButton.setOnClickListener {
            val providers = (presets.map { it.provider }.distinct() + "自定义").toTypedArray()
            AlertDialog.Builder(context)
                .setTitle("选择模型厂牌")
                .setItems(providers) { _, which ->
                    providerName = providers[which]
                    providerPresets = presets.filter { it.provider == providerName }
                    if (providerName != "自定义") {
                        providerPresets.firstOrNull()?.let(::applyPreset)
                    } else {
                        refreshPresetButtons()
                    }
                }
                .show()
        }

        modelButton.setOnClickListener {
            if (providerName == "自定义") {
                toastOnUi("自定义模式下直接填写模型地址和模型名")
                return@setOnClickListener
            }
            val names = providerPresets.map { it.label }.toTypedArray()
            AlertDialog.Builder(context)
                .setTitle("选择 $providerName 模型")
                .setItems(names) { _, which ->
                    providerPresets.getOrNull(which)?.let(::applyPreset)
                }
                .show()
        }
        refreshPresetButtons()

        label("密钥")
        val modelKey = secretEdit(config.modelKey, "可选，测试链接会带 Bearer token")

        label("密钥管理")
        modelProfileNameView = TextView(context).apply {
            text = "当前密钥配置：${AiBgMusic.selectedModelProfileName.ifBlank { "未选择" }}"
            textSize = 15f
        }
        root.addView(modelProfileNameView)
        root.addView(MaterialButton(context).apply {
            text = "管理密钥/模型配置"
            setOnClickListener {
                showModelProfilesDialog(
                    currentProvider = providerName,
                    currentUrl = modelUrl.text?.toString().orEmpty(),
                    currentName = modelName.text?.toString().orEmpty(),
                    currentKey = modelKey.text?.toString().orEmpty()
                ) { profile ->
                    providerName = profile.provider.ifBlank {
                        presets.firstOrNull {
                            it.baseUrl == profile.modelUrl && it.model == profile.modelName
                        }?.provider ?: "自定义"
                    }
                    providerPresets = presets.filter { it.provider == providerName }
                    modelUrl.setText(profile.modelUrl)
                    modelName.setText(profile.modelName)
                    modelKey.setText(profile.modelKey)
                    modelKey.setSelection(modelKey.text?.length ?: 0)
                    refreshPresetButtons()
                }
            }
        })

        root.addView(MaterialButton(context).apply {
            text = "自定义接入"
            setOnClickListener {
                showCustomModelDialog(
                    modelUrl.text?.toString().orEmpty(),
                    modelName.text?.toString().orEmpty(),
                    modelKey.text?.toString().orEmpty()
                ) { url, name, key ->
                    providerName = "自定义"
                    providerPresets = emptyList()
                    modelUrl.setText(url)
                    modelName.setText(name)
                    modelKey.setText(key)
                    modelKey.setSelection(modelKey.text?.length ?: 0)
                    refreshPresetButtons()
                }
            }
        })

        root.addView(MaterialButton(context).apply {
            text = "测试链接"
            setOnClickListener {
                saveFromViews(musicDir, modelUrl, modelName, modelKey, null, providerName)
                lifecycleScope.launch {
                    val message = withContext(Dispatchers.IO) {
                        AiBgMusic.testModel().getOrElse { e -> "测试失败：${e.localizedMessage}" }
                    }
                    toastOnUi(message)
                }
            }
        })

        label("提示词管理")
        promptNameView = TextView(context).apply {
            text = "当前方案：${AiBgMusic.selectedPromptName}"
            textSize = 15f
        }
        root.addView(promptNameView)
        root.addView(MaterialButton(context).apply {
            text = "管理提示词方案"
            setOnClickListener { showPromptProfilesDialog() }
        })

        label("音乐切换频率")
        val frequencyText = TextView(context)
        root.addView(frequencyText)
        val frequencySlider = Slider(context).apply {
            valueFrom = 0f
            valueTo = 2f
            stepSize = 1f
            value = config.frequency.toFloat()
        }
        fun updateFrequencyText(value: Float) {
            frequencyText.text = when (value.toInt()) {
                AiBgMusic.FREQUENCY_BOOK -> "整本书一种基调音乐反复播放"
                AiBgMusic.FREQUENCY_CHAPTER -> "不同章节切换不同背景音乐"
                else -> "一个场景一个音乐，读到场景边界切换"
            }
        }
        updateFrequencyText(frequencySlider.value)
        frequencySlider.addOnChangeListener { _, value, _ -> updateFrequencyText(value) }
        root.addView(frequencySlider)

        label("场景音乐跨度")
        val scenesPerMusicText = TextView(context)
        root.addView(scenesPerMusicText)
        val scenesPerMusicSlider = Slider(context).apply {
            valueFrom = 1f
            valueTo = 10f
            stepSize = 1f
            value = config.scenesPerMusic.toFloat()
        }
        fun updateScenesPerMusicText(value: Float) {
            scenesPerMusicText.text = if (value.toInt() <= 1) {
                "每 1 个场景切换一次音乐"
            } else {
                "每 ${value.toInt()} 个场景共用一种音乐"
            }
        }
        updateScenesPerMusicText(scenesPerMusicSlider.value)
        scenesPerMusicSlider.addOnChangeListener { _, value, _ -> updateScenesPerMusicText(value) }
        root.addView(scenesPerMusicSlider)

        label("播放列表预生成")
        val preloadWholeBook = CheckBox(context).apply {
            text = "提前分析整本书"
            isChecked = config.preloadWholeBook
        }
        root.addView(preloadWholeBook)
        val preloadText = TextView(context)
        root.addView(preloadText)
        val preloadSlider = Slider(context).apply {
            valueFrom = 1f
            valueTo = 30f
            stepSize = 1f
            value = config.preloadChapters.coerceIn(1, 30).toFloat()
        }
        fun updatePreloadText(value: Float) {
            preloadText.text = "不选整本书时，提前生成当前章 + 后面 ${value.toInt()} 章播放列表"
        }
        updatePreloadText(preloadSlider.value)
        preloadSlider.addOnChangeListener { _, value, _ -> updatePreloadText(value) }
        root.addView(preloadSlider)

        label("AI 候选音乐数量")
        val candidateLimitText = TextView(context)
        root.addView(candidateLimitText)
        val candidateLimitSlider = Slider(context).apply {
            valueFrom = 50f
            valueTo = 500f
            stepSize = 10f
            value = config.promptMusicCandidateLimit.coerceIn(50, 500).toFloat()
        }
        fun updateCandidateLimitText(value: Float) {
            candidateLimitText.text = "每次分析最多把 ${value.toInt()} 首候选音乐发给 AI；本地音乐库仍完整读取。"
        }
        updateCandidateLimitText(candidateLimitSlider.value)
        candidateLimitSlider.addOnChangeListener { _, value, _ -> updateCandidateLimitText(value) }
        root.addView(candidateLimitSlider)

        label("背景音乐音量")
        val volumeText = TextView(context)
        root.addView(volumeText)
        val volumeSlider = Slider(context).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            value = config.volume.toFloat()
        }
        fun updateVolumeText(value: Float) {
            volumeText.text = "${value.toInt()}%"
        }
        updateVolumeText(volumeSlider.value)
        volumeSlider.addOnChangeListener { _, value, _ -> updateVolumeText(value) }
        root.addView(volumeSlider)

        root.addView(MaterialButton(context).apply {
            text = "保存"
            setOnClickListener {
                saveFromViews(musicDir, modelUrl, modelName, modelKey, frequencySlider to volumeSlider, providerName)
                AiBgMusic.enabled = enableBgmCheckBox.isChecked
                AiBgMusic.scenesPerMusic = scenesPerMusicSlider.value.toInt()
                AiBgMusic.preloadWholeBook = preloadWholeBook.isChecked
                AiBgMusic.preloadChapters = preloadSlider.value.toInt()
                AiBgMusic.promptMusicCandidateLimit = candidateLimitSlider.value.toInt()
                toastOnUi("智能背景音乐设置已保存")
                dismissAllowingStateLoss()
            }
        })

        return scrollView
    }

    private fun saveFromViews(
        musicDir: EditText,
        modelUrl: EditText,
        modelName: EditText,
        modelKey: EditText,
        sliders: Pair<Slider, Slider>?,
        providerName: String = "",
    ) {
        val old = AiBgMusic.config()
        AiBgMusic.save(
            old.copy(
                musicDir = musicDir.text?.toString().orEmpty().trim().ifBlank { AiBgMusic.musicDir },
                modelUrl = modelUrl.text?.toString().orEmpty(),
                modelName = modelName.text?.toString().orEmpty(),
                modelKey = modelKey.text?.toString().orEmpty(),
                prompts = old.prompts,
                frequency = sliders?.first?.value?.toInt() ?: old.frequency,
                volume = sliders?.second?.value?.toInt() ?: old.volume,
            )
        )
        AiBgMusic.upsertSelectedModelProfile(providerName)
        modelProfileNameView?.text = "当前密钥配置：${AiBgMusic.selectedModelProfileName.ifBlank { "未选择" }}"
    }

    private fun showCustomModelDialog(
        initUrl: String,
        initName: String,
        initKey: String,
        onApply: (url: String, name: String, key: String) -> Unit
    ) {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx())
        }

        val urlEdit = EditText(context).apply {
            hint = "模型地址，例如 https://open.bigmodel.cn/api/paas/v4"
            setText(initUrl.ifBlank { AiBgMusic.modelUrl })
        }

        val nameEdit = EditText(context).apply {
            hint = "模型名，例如 glm-4.5-flash"
            setText(initName.ifBlank { AiBgMusic.modelName })
        }

        val keyEdit = EditText(context).apply {
            hint = "API Key，可填 sk-xxx 或 Bearer sk-xxx"
            setText(initKey.ifBlank { AiBgMusic.modelKey })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }

        root.addView(urlEdit)
        root.addView(nameEdit)
        root.addView(secretRow(keyEdit))

        AlertDialog.Builder(context)
            .setTitle("自定义模型接入")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val url = urlEdit.text?.toString()?.trim().orEmpty()
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val key = keyEdit.text?.toString()?.trim().orEmpty()

                if (url.isBlank() || name.isBlank()) {
                    toastOnUi("模型地址和模型名不能为空")
                    return@setPositiveButton
                }

                onApply(url, name, key)
            }
            .show()
    }

    private fun showModelProfilesDialog(
        currentProvider: String,
        currentUrl: String,
        currentName: String,
        currentKey: String,
        onApply: (AiBgMusic.ModelProfile) -> Unit
    ) {
        val context = requireContext()
        val profiles = AiBgMusic.modelProfiles()
        val selectedName = AiBgMusic.selectedModelProfileName

        val listRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }

        var dialog: AlertDialog? = null

        fun selectProfile(profile: AiBgMusic.ModelProfile) {
            AiBgMusic.selectModelProfile(profile)
            modelProfileNameView?.text = "当前密钥配置：${profile.name}"
            onApply(profile)
            toastOnUi("已切换模型配置")
            dialog?.dismiss()
        }

        if (profiles.isEmpty()) {
            listRoot.addView(TextView(context).apply {
                text = "还没有保存的模型配置"
                textSize = 15f
                setPadding(8.dpToPx())
            })
        }

        profiles.forEach { profile ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
            }

            val radio = android.widget.RadioButton(context).apply {
                isChecked = profile.name == selectedName
                contentDescription = "选择 ${profile.name}"
                setOnClickListener { selectProfile(profile) }
            }

            val title = TextView(context).apply {
                text = profile.name
                textSize = 16f
                setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
                setOnClickListener {
                    dialog?.dismiss()
                    showModelProfileEditor(profile, onApply)
                }
            }

            val subtitle = TextView(context).apply {
                text = listOf(profile.provider, profile.modelName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { profile.modelUrl }
                textSize = 12f
                setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
            }

            val textBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle)
            }

            val edit = TextView(context).apply {
                text = "编辑"
                textSize = 14f
                setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
                setOnClickListener {
                    dialog?.dismiss()
                    showModelProfileEditor(profile, onApply)
                }
            }

            row.addView(radio)
            row.addView(
                textBox,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(edit)

            listRoot.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val scrollView = android.widget.ScrollView(context).apply {
            addView(listRoot)
        }

        dialog = AlertDialog.Builder(context)
            .setTitle("密钥/模型配置")
            .setView(scrollView)
            .setNegativeButton("取消", null)
            .setPositiveButton("新增") { _, _ ->
                showModelProfileEditor(
                    AiBgMusic.ModelProfile(
                        name = "新配置",
                        provider = currentProvider.takeUnless { it == "自定义" }.orEmpty(),
                        modelUrl = currentUrl,
                        modelName = currentName,
                        modelKey = currentKey,
                    ),
                    onApply
                )
            }
            .create()

        dialog?.show()
    }

    private fun showModelProfileEditor(
        profile: AiBgMusic.ModelProfile,
        onApply: (AiBgMusic.ModelProfile) -> Unit
    ) {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx())
        }

        val nameEdit = EditText(context).apply {
            hint = "配置名称，例如 DeepSeek 日常"
            setText(profile.name)
        }
        val providerEdit = EditText(context).apply {
            hint = "厂牌，可选"
            setText(profile.provider)
        }
        val urlEdit = EditText(context).apply {
            hint = "模型地址，例如 https://api.openai.com/v1"
            setText(profile.modelUrl)
        }
        val modelEdit = EditText(context).apply {
            hint = "模型名，例如 gpt-4o-mini"
            setText(profile.modelName)
        }
        val keyEdit = EditText(context).apply {
            hint = "API Token"
            setText(profile.modelKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }

        root.addView(nameEdit)
        root.addView(providerEdit)
        root.addView(urlEdit)
        root.addView(modelEdit)
        root.addView(secretRow(keyEdit))

        AlertDialog.Builder(context)
            .setTitle("编辑密钥/模型配置")
            .setView(root)
            .setPositiveButton("保存并选择") { _, _ ->
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val provider = providerEdit.text?.toString()?.trim().orEmpty()
                val url = urlEdit.text?.toString()?.trim().orEmpty()
                val model = modelEdit.text?.toString()?.trim().orEmpty()
                val key = keyEdit.text?.toString()?.trim().orEmpty()

                if (name.isBlank() || url.isBlank() || model.isBlank()) {
                    toastOnUi("配置名称、模型地址、模型名不能为空")
                    return@setPositiveButton
                }

                val newProfile = AiBgMusic.ModelProfile(name, provider, url, model, key)
                val profiles = AiBgMusic.modelProfiles().toMutableList()
                val index = profiles.indexOfFirst { it.name == profile.name }
                    .takeIf { it >= 0 }
                    ?: profiles.indexOfFirst { it.name == name }

                if (index >= 0) profiles[index] = newProfile else profiles.add(newProfile)

                AiBgMusic.saveModelProfiles(profiles)
                AiBgMusic.selectModelProfile(newProfile)
                modelProfileNameView?.text = "当前密钥配置：$name"
                onApply(newProfile)
                toastOnUi("模型配置已保存并选择")
            }
            .setNeutralButton("删除") { _, _ ->
                val profiles = AiBgMusic.modelProfiles()
                    .filterNot { it.name == profile.name }
                AiBgMusic.saveModelProfiles(profiles)
                if (AiBgMusic.selectedModelProfileName == profile.name) {
                    AiBgMusic.selectedModelProfileName = profiles.firstOrNull()?.name.orEmpty()
                }
                modelProfileNameView?.text = "当前密钥配置：${AiBgMusic.selectedModelProfileName.ifBlank { "未选择" }}"
                toastOnUi("模型配置已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun secretRow(editText: EditText): LinearLayout {
        val context = requireContext()
        var visible = false
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(editText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                ImageButton(context).apply {
                    setImageResource(R.drawable.ic_visibility_off)
                    contentDescription = "显示密钥"
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setPadding(10.dpToPx())
                    setOnClickListener {
                        visible = !visible
                        editText.transformationMethod = if (visible) {
                            HideReturnsTransformationMethod.getInstance()
                        } else {
                            PasswordTransformationMethod.getInstance()
                        }
                        setImageResource(if (visible) R.drawable.ic_visibility_on else R.drawable.ic_visibility_off)
                        contentDescription = if (visible) "隐藏密钥" else "显示密钥"
                        editText.setSelection(editText.text?.length ?: 0)
                    }
                },
                LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
            )
        }
    }

    private fun showPromptProfilesDialog() {
        val context = requireContext()
        val profiles = AiBgMusic.promptProfiles()
        val selectedName = AiBgMusic.selectedPromptName

        val listRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }

        var dialog: AlertDialog? = null

        fun selectProfile(profile: AiBgMusic.PromptProfile) {
            AiBgMusic.selectedPromptName = profile.name
            promptNameView?.text = "当前方案：${profile.name}"
            toastOnUi("已选择提示词方案")
            dialog?.dismiss()
        }

        profiles.forEach { profile ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
            }

            val radio = android.widget.RadioButton(context).apply {
                isChecked = profile.name == selectedName
                contentDescription = "选择 ${profile.name}"
                setOnClickListener {
                    selectProfile(profile)
                }
            }

            val title = android.widget.TextView(context).apply {
                text = profile.name
                textSize = 16f
                setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
                setOnClickListener {
                    dialog?.dismiss()
                    showPromptProfileEditor(profile)
                }
            }

            val edit = android.widget.TextView(context).apply {
                text = "编辑"
                textSize = 14f
                setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
                setOnClickListener {
                    dialog?.dismiss()
                    showPromptProfileEditor(profile)
                }
            }

            row.addView(radio)
            row.addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(edit)

            listRoot.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val scrollView = android.widget.ScrollView(context).apply {
            addView(listRoot)
        }

        dialog = AlertDialog.Builder(context)
            .setTitle("提示词方案")
            .setView(scrollView)
            .setNegativeButton("取消", null)
            .setPositiveButton("新增") { _, _ ->
                showPromptProfileEditor(AiBgMusic.PromptProfile("新提示词", ""))
            }
            .create()

        dialog?.show()
    }

    private fun showPromptProfileEditor(profile: AiBgMusic.PromptProfile) {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx())
        }
        val nameEdit = EditText(context).apply {
            hint = "方案名称"
            setText(profile.name)
        }
        val promptEdit = EditText(context).apply {
            hint = "提示词内容"
            minLines = 8
            setText(profile.prompt)
        }
        root.addView(nameEdit)
        root.addView(promptEdit)

        AlertDialog.Builder(context)
            .setTitle("编辑提示词方案")
            .setView(root)
            .setPositiveButton("保存并选择") { _, _ ->
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val prompt = promptEdit.text?.toString().orEmpty()
                if (name.isBlank() || prompt.isBlank()) {
                    toastOnUi("名称和提示词不能为空")
                    return@setPositiveButton
                }
                val profiles = AiBgMusic.promptProfiles().toMutableList()
                val oldIndex = profiles.indexOfFirst { it.name == profile.name }
                val newProfile = AiBgMusic.PromptProfile(name, prompt)
                if (oldIndex >= 0) profiles[oldIndex] = newProfile else profiles.add(newProfile)
                AiBgMusic.savePromptProfiles(profiles)
                AiBgMusic.selectedPromptName = name
                promptNameView?.text = "当前方案：$name"
                toastOnUi("提示词方案已保存并选择")
            }
            .setNeutralButton("删除") { _, _ ->
                val profiles = AiBgMusic.promptProfiles()
                    .filterNot { it.name == profile.name }
                AiBgMusic.savePromptProfiles(profiles)
                promptNameView?.text = "当前方案：${AiBgMusic.selectedPromptName}"
                toastOnUi("提示词方案已删除")
            }
            .setNegativeButton("仅保存") { _, _ ->
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val prompt = promptEdit.text?.toString().orEmpty()
                if (name.isBlank() || prompt.isBlank()) {
                    toastOnUi("名称和提示词不能为空")
                    return@setNegativeButton
                }

                val wasSelected = AiBgMusic.selectedPromptName == profile.name
                val profiles = AiBgMusic.promptProfiles().toMutableList()
                val oldIndex = profiles.indexOfFirst { it.name == profile.name }
                val newProfile = AiBgMusic.PromptProfile(name, prompt)

                if (oldIndex >= 0) {
                    profiles[oldIndex] = newProfile
                } else {
                    profiles.add(newProfile)
                }

                AiBgMusic.savePromptProfiles(profiles)

                if (wasSelected) {
                    AiBgMusic.selectedPromptName = name
                    promptNameView?.text = "当前方案：$name"
                } else {
                    promptNameView?.text = "当前方案：${AiBgMusic.selectedPromptName}"
                }

                toastOnUi("提示词方案已保存")
            }
            .show()
    }

    interface Callback {
        fun selectAiBgMusicDir()
    }

    private fun modelPresets(): List<ModelPreset> {
        return listOf(
            ModelPreset("DeepSeek", "DeepSeek V4 Flash（快，适合日常场景分析）", "https://api.deepseek.com", "deepseek-v4-flash"),
            ModelPreset("DeepSeek", "DeepSeek V4 Pro（更稳，适合复杂章节）", "https://api.deepseek.com", "deepseek-v4-pro"),
            ModelPreset("智谱", "GLM-4.5-Flash（快，成本低）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-flash"),
            ModelPreset("智谱", "GLM-4.5-Air（质量更稳）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air"),
            ModelPreset("美团 LongCat", "LongCat-Flash-Lite（轻量，预生成用）", "https://api.longcat.chat/openai", "LongCat-Flash-Lite"),
            ModelPreset("美团 LongCat", "LongCat-Flash-Chat（通用，稳定）", "https://api.longcat.chat/openai", "LongCat-Flash-Chat"),
            ModelPreset("千问 Qwen", "qwen-flash（轻量，速度优先）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash"),
            ModelPreset("千问 Qwen", "qwen-plus（推荐，结构化更稳）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        )
    }
}
