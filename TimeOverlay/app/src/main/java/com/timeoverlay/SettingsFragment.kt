package com.timeoverlay

import android.Manifest
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timeoverlay.databinding.FragmentSettingsBinding

/** Вкладка настроек: разрешения, главный тумблер, вид оверлея, поведение. */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: Prefs
    private lateinit var preview: OverlayView

    private val bgChips = mutableListOf<View>()
    private val textChips = mutableListOf<View>()

    /** Гасит слушателей, пока UI заполняется из настроек. */
    private var bindingUi = false

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())
        setupPreview()
        setupPermissionButtons()
        setupToggle()
        setupSliders()
        setupPalettes()
        setupBehaviour()
    }

    override fun onResume() {
        super.onResume()
        bindUiFromPrefs()
        refreshPermissions()
    }

    override fun onDestroyView() {
        bgChips.clear()
        textChips.clear()
        _binding = null
        super.onDestroyView()
    }

    // --- превью ---

    private fun setupPreview() {
        preview = OverlayView(requireContext())
        binding.previewContainer.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        preview.setTime("12:34")
    }

    private fun refreshPreview() {
        preview.setStyle(
            textSizeSp = prefs.textSizeSp,
            cornerPercent = prefs.cornerPercent,
            bgColor = prefs.bgColor,
            textColor = prefs.textColor,
            opacityPercent = prefs.opacityPercent
        )
    }

    // --- разрешения ---

    private fun setupPermissionButtons() {
        binding.permOverlayButton.setOnClickListener {
            settingsLauncher.launch(PermissionHelper.overlayIntent(requireContext()))
        }
        binding.permUsageButton.setOnClickListener {
            settingsLauncher.launch(PermissionHelper.usageAccessIntent())
        }
        binding.permNotifButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun refreshPermissions() {
        if (_binding == null) return
        val context = requireContext()
        val overlay = PermissionHelper.hasOverlay(context)
        val usage = PermissionHelper.hasUsageAccess(context)
        val notifications = PermissionHelper.hasNotifications(context)

        bindStatus(binding.permOverlayStatus, binding.permOverlayButton, overlay)
        bindStatus(binding.permUsageStatus, binding.permUsageButton, usage)
        bindStatus(binding.permNotifStatus, binding.permNotifButton, notifications)

        val ready = overlay && usage
        binding.switchEnabled.isEnabled = ready
        binding.toggleHint.visibility = if (ready) View.GONE else View.VISIBLE

        if (!ready && binding.switchEnabled.isChecked) {
            bindingUi = true
            binding.switchEnabled.isChecked = false
            bindingUi = false
            prefs.enabled = false
            OverlayService.stop(context)
        }
    }

    private fun bindStatus(status: TextView, button: View, granted: Boolean) {
        status.setText(if (granted) R.string.perm_granted else R.string.perm_missing)
        button.visibility = if (granted) View.GONE else View.VISIBLE
    }

    // --- тумблер ---

    private fun setupToggle() {
        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (bindingUi) return@setOnCheckedChangeListener
            val context = requireContext()
            if (checked) {
                if (!PermissionHelper.hasRequired(context)) {
                    binding.switchEnabled.isChecked = false
                    Toast.makeText(context, R.string.toggle_blocked, Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PermissionHelper.hasNotifications(context)
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                prefs.enabled = true
                OverlayService.start(context)
            } else {
                prefs.enabled = false
                OverlayService.stop(context)
            }
        }
    }

    // --- слайдеры ---

    private fun setupSliders() {
        binding.sliderSize.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.sizePercent = value.toInt()
            refreshPreview()
        }
        binding.sliderCorner.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.cornerPercent = value.toInt()
            refreshPreview()
        }
        binding.sliderOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.opacityPercent = value.toInt()
            refreshPreview()
        }
    }

    // --- цвета ---

    private fun setupPalettes() {
        buildPalette(binding.paletteBg, bgChips) { color ->
            prefs.bgColor = color
            refreshPreview()
            markSelected(bgChips, color)
        }
        buildPalette(binding.paletteText, textChips) { color ->
            prefs.textColor = color
            refreshPreview()
            markSelected(textChips, color)
        }
        binding.buttonCustomBg.setOnClickListener {
            askCustomColor(prefs.bgColor) { color ->
                prefs.bgColor = color
                refreshPreview()
                markSelected(bgChips, color)
            }
        }
        binding.buttonCustomText.setOnClickListener {
            askCustomColor(prefs.textColor) { color ->
                prefs.textColor = color
                refreshPreview()
                markSelected(textChips, color)
            }
        }
    }

    private fun buildPalette(row: LinearLayout, chips: MutableList<View>, onPick: (Int) -> Unit) {
        val size = dp(36)
        val gap = dp(10)
        Prefs.PALETTE.forEach { color ->
            val chip = View(requireContext())
            chip.background = chipDrawable(color, selected = false)
            chip.tag = color
            chip.setOnClickListener { onPick(color) }
            row.addView(chip, LinearLayout.LayoutParams(size, size).apply { marginEnd = gap })
            chips += chip
        }
    }

    private fun chipDrawable(color: Int, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(
            dp(if (selected) 3 else 1),
            if (selected) resolveAttr(com.google.android.material.R.attr.colorPrimary) else Color.GRAY
        )
    }

    private fun markSelected(chips: List<View>, color: Int) {
        chips.forEach { chip ->
            val chipColor = chip.tag as Int
            chip.background = chipDrawable(chipColor, chipColor == color)
        }
    }

    private fun askCustomColor(current: Int, onPick: (Int) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.custom_color_hint)
            setText(String.format("%08X", current))
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.custom_color)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val parsed = parseColor(input.text.toString())
                if (parsed == null) {
                    Toast.makeText(requireContext(), R.string.custom_color_bad, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    onPick(parsed)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun parseColor(raw: String): Int? {
        val hex = raw.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        return try {
            Color.parseColor("#$hex")
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // --- поведение ---

    private fun setupBehaviour() {
        binding.graceGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || bindingUi) return@addOnButtonCheckedListener
            prefs.graceSeconds = when (checkedId) {
                R.id.grace15 -> 15
                R.id.grace60 -> 60
                R.id.grace120 -> 120
                else -> 30
            }
        }
        binding.switchHideLauncher.setOnCheckedChangeListener { _, checked ->
            if (!bindingUi) prefs.hideOnLauncher = checked
        }
        binding.switchAutostart.setOnCheckedChangeListener { _, checked ->
            if (!bindingUi) prefs.autostart = checked
        }
    }

    // --- заполнение UI из настроек ---

    private fun bindUiFromPrefs() {
        bindingUi = true
        binding.switchEnabled.isChecked = prefs.enabled
        binding.sliderSize.value = prefs.sizePercent.toFloat()
        binding.sliderCorner.value = prefs.cornerPercent.toFloat()
        binding.sliderOpacity.value =
            prefs.opacityPercent.toFloat().coerceAtLeast(Prefs.MIN_OPACITY.toFloat())
        binding.graceGroup.check(
            when (prefs.graceSeconds) {
                15 -> R.id.grace15
                60 -> R.id.grace60
                120 -> R.id.grace120
                else -> R.id.grace30
            }
        )
        binding.switchHideLauncher.isChecked = prefs.hideOnLauncher
        binding.switchAutostart.isChecked = prefs.autostart
        bindingUi = false

        markSelected(bgChips, prefs.bgColor)
        markSelected(textChips, prefs.textColor)
        refreshPreview()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun resolveAttr(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}
