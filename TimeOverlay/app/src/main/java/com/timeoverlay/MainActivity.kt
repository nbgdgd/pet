package com.timeoverlay

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.timeoverlay.databinding.ActivityMainBinding

/** Хост двух вкладок: настройки и статистика. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(
                when (item.itemId) {
                    R.id.tabStats -> StatsFragment()
                    else -> SettingsFragment()
                }
            )
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.tabSettings
        }
    }

    override fun onResume() {
        super.onResume()
        // Сервис мог быть убит системой — поднимаем, раз тумблер включён.
        if (prefs.enabled && PermissionHelper.hasRequired(this)) OverlayService.start(this)
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }
}
