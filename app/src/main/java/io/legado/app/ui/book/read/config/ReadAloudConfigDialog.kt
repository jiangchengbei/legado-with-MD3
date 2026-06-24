package io.legado.app.ui.book.read.config

//import io.legado.app.lib.theme.backgroundColor
//import io.legado.app.lib.theme.primaryColor
// 【新增引用】为了显示清理成功的提示
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.TTSCacheUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = LinearLayout(requireContext())
        //view.setBackgroundColor(requireContext().backgroundColor)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readAloudPreferTag)
            .commit()
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val speakEngineSummary: String
            get() {
                val ttsEngine = ReadAloud.ttsEngine
                    ?: return getString(R.string.system_tts)
                if (StringUtils.isNumeric(ttsEngine)) {
                    return appDb.httpTTSDao.getName(ttsEngine.toLong())
                        ?: getString(R.string.system_tts)
                }
                return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                    ?: getString(R.string.system_tts)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            upPreferenceSummary(PreferKey.audioPreDownloadNum)
            upPreferenceSummary(PreferKey.audioCacheCleanTime)
            upPreferenceSummary("ttsWakeRetry")
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                it.isEnabled = AppConfig.ignoreAudioFocus
            }

            findPreference<Preference>("clear_cache")?.let {
                it.summary = getString(R.string.clear_cache)
                it.setOnPreferenceClickListener {
                    TTSCacheUtils.clearTtsCache()
                    toastOnUi("音频缓存已清理")
                    true
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            //listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.audioPreDownloadNum -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.read_aloud_preload))
                        .setMaxValue(200)
                        .setMinValue(0)
                        .setValue(AppConfig.audioPreDownloadNum)
                        .setCustomButton((R.string.btn_default_s)) {
                            putPrefInt(PreferKey.audioPreDownloadNum, 10)
                            upPreferenceSummary(PreferKey.audioPreDownloadNum)
                        }
                        .show {
                            putPrefInt(PreferKey.audioPreDownloadNum, it)
                            upPreferenceSummary(PreferKey.audioPreDownloadNum)
                        }
                }

                PreferKey.audioCacheCleanTime -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.audio_cache_clean_time))
                        .setMaxValue(10000)
                        .setMinValue(0)
                        .setValue(AppConfig.audioCacheCleanTimeOrgin)
                        .setCustomButton((R.string.btn_default_s)) {
                            putPrefInt(PreferKey.audioCacheCleanTime, 10)
                            upPreferenceSummary(PreferKey.audioCacheCleanTime)
                        }
                        .show {
                            putPrefInt(PreferKey.audioCacheCleanTime, it)
                            upPreferenceSummary(PreferKey.audioCacheCleanTime)
                        }
                }

                "ttsWakeRetry" -> {
                    showTtsWakeRetryDialog()
                }

                PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
                "sysTtsConfig" -> IntentHelp.openTTSSetting()
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ignoreAudioFocus -> {
                    findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                        it.isEnabled = AppConfig.ignoreAudioFocus
                    }
                }
            }
        }

        private fun upPreferenceSummary(preference: Preference?, value: String) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(value)
                    preference.summary = if (index >= 0) preference.entries[index] else null
                }


                else -> {
                    preference?.summary = value
                }
            }
        }

        private fun upPreferenceSummary(preferenceKey: String, value: String? = null) {
            val preference = findPreference<Preference>(preferenceKey) ?: return
            when (preferenceKey) {
                PreferKey.audioPreDownloadNum -> {
                    preference.summary = getString(
                        R.string.read_aloud_preload_summary,
                        AppConfig.audioPreDownloadNum
                    )
                }

                PreferKey.audioCacheCleanTime -> {
                    preference.summary = getString(
                        R.string.audio_cache_clean_time_summary,
                        AppConfig.audioCacheCleanTimeOrgin
                    )
                }

                "ttsWakeRetry" -> {
                    val count = AppConfig.ttsWakeRetryCount
                    preference.summary = if (count == 0) {
                        getString(R.string.tts_wake_retry_summary_off)
                    } else {
                        getString(R.string.tts_wake_retry_summary, AppConfig.ttsWakeRetryTimeout, count)
                    }
                }

                else -> preference.summary = value
            }
        }

        override fun upSpeakEngineSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsEngine),
                speakEngineSummary
            )
        }

        private fun showTtsWakeRetryDialog() {
            val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.tts_wake_retry_title))
                .setView(R.layout.dialog_tts_wake_retry)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.btn_default_s, null)
            val dialog = dialogBuilder.show()

            val npTimeout = dialog.findViewById<NumberPicker>(R.id.np_timeout)!!
            val tilTimeout = dialog.findViewById<TextInputLayout>(R.id.til_timeout)!!
            val etTimeout = dialog.findViewById<TextInputEditText>(R.id.et_timeout)!!
            val btnSwitchTimeout = dialog.findViewById<MaterialButton>(R.id.btn_switch_timeout)!!
            npTimeout.minValue = 15
            npTimeout.maxValue = 120
            npTimeout.value = AppConfig.ttsWakeRetryTimeout

            val npCount = dialog.findViewById<NumberPicker>(R.id.np_count)!!
            val tilCount = dialog.findViewById<TextInputLayout>(R.id.til_count)!!
            val etCount = dialog.findViewById<TextInputEditText>(R.id.et_count)!!
            val btnSwitchCount = dialog.findViewById<MaterialButton>(R.id.btn_switch_count)!!
            npCount.minValue = 0
            npCount.maxValue = 10
            npCount.value = AppConfig.ttsWakeRetryCount

            var timeoutInputMode = false
            var countInputMode = false

            btnSwitchTimeout.setOnClickListener {
                timeoutInputMode = !timeoutInputMode
                if (timeoutInputMode) {
                    npTimeout.visibility = View.GONE
                    tilTimeout.visibility = View.VISIBLE
                    tilTimeout.hint = "输入范围: 15 - 120"
                    etTimeout.setText(npTimeout.value.toString())
                } else {
                    npTimeout.visibility = View.VISIBLE
                    tilTimeout.visibility = View.GONE
                    tilTimeout.error = null
                }
            }

            btnSwitchCount.setOnClickListener {
                countInputMode = !countInputMode
                if (countInputMode) {
                    npCount.visibility = View.GONE
                    tilCount.visibility = View.VISIBLE
                    tilCount.hint = "输入范围: 0 - 10"
                    etCount.setText(npCount.value.toString())
                } else {
                    npCount.visibility = View.VISIBLE
                    tilCount.visibility = View.GONE
                    tilCount.error = null
                }
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val timeout = if (timeoutInputMode) {
                    val num = etTimeout.text?.toString()?.toIntOrNull()
                    if (num == null || num !in 15..120) {
                        tilTimeout.error = "请输入 15 - 120 范围内的数字"
                        return@setOnClickListener
                    }
                    num
                } else {
                    npTimeout.clearFocus()
                    npTimeout.value
                }
                val count = if (countInputMode) {
                    val num = etCount.text?.toString()?.toIntOrNull()
                    if (num == null || num !in 0..10) {
                        tilCount.error = "请输入 0 - 10 范围内的数字"
                        return@setOnClickListener
                    }
                    num
                } else {
                    npCount.clearFocus()
                    npCount.value
                }
                putPrefInt(PreferKey.ttsWakeRetryTimeout, timeout)
                putPrefInt(PreferKey.ttsWakeRetryCount, count)
                upPreferenceSummary("ttsWakeRetry")
                dialog.dismiss()
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                npTimeout.value = 20
                npCount.value = 3
                etTimeout.setText("20")
                etCount.setText("3")
                tilTimeout.error = null
                tilCount.error = null
            }
        }
    }
}
