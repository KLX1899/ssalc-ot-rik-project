// ManageClassesActivity.kt
package com.example.myapplication

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial

class ManageClassesActivity : AppCompatActivity() {

    private lateinit var classListContainer: LinearLayout
    private lateinit var tvClassCount: TextView
    private val classEntries = mutableListOf<ClassEntryHolder>()

    // Holds references to one card's views
    private data class ClassEntryHolder(
        val discoveredClass: ClassDiscoveryManager.DiscoveredClass,
        val platformToggle: MaterialButtonToggleGroup,
        val btnLms: MaterialButton,
        val btnNima: MaterialButton,
        val switchEnabled: SwitchMaterial
    )

    private val dayDisplayMap = mapOf(
        "sat" to "شنبه", "sun" to "یکشنبه", "mon" to "دوشنبه",
        "tue" to "سه‌شنبه", "wed" to "چهارشنبه", "thu" to "پنجشنبه",
        "fri" to "جمعه"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_classes)

        val root = findViewById<LinearLayout>(R.id.manageRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val pad = (12 * resources.displayMetrics.density).toInt()
            v.setPadding(sys.left + pad, sys.top + pad, sys.right + pad, sys.bottom + pad)
            insets
        }

        classListContainer = findViewById(R.id.classListContainer)
        tvClassCount = findViewById(R.id.tvClassCount)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnSaveSchedule).setOnClickListener {
            saveAll()
        }

        loadAndDisplay()
    }

    private fun loadAndDisplay() {
        classEntries.clear()
        classListContainer.removeAllViews()

        val classes = ClassDiscoveryManager.loadDiscoveredClasses(this)

        if (classes.isEmpty()) {
            tvClassCount.setText(R.string.no_classes_found)
            return
        }

        tvClassCount.text = getString(R.string.class_count, classes.size)

        for (cls in classes) {
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_class_card, classListContainer, false)

            val tvName = card.findViewById<TextView>(R.id.tvClassName)
            val tvSchedule = card.findViewById<TextView>(R.id.tvClassSchedule)
            val toggle = card.findViewById<MaterialButtonToggleGroup>(R.id.platformToggle)
            val btnLms = card.findViewById<MaterialButton>(R.id.btnLms)
            val btnNima = card.findViewById<MaterialButton>(R.id.btnNima)
            val switchEnabled = card.findViewById<SwitchMaterial>(R.id.switchEnabled)

            // Display class name
            tvName.text = cls.name

            // Display schedule as readable text
            val scheduleLines = cls.days.map { day ->
                "${dayDisplayMap[day] ?: day}  ${cls.start} - ${cls.end}"
            }
            tvSchedule.text = scheduleLines.joinToString("\n")

            // Set current platform selection
            if (cls.platform.uppercase() == "NIMA") {
                toggle.check(btnNima.id)
            } else {
                toggle.check(btnLms.id)
            }

            // Set enabled state
            switchEnabled.isChecked = cls.enabled

            classListContainer.addView(card)

            classEntries.add(
                ClassEntryHolder(cls, toggle, btnLms, btnNima, switchEnabled)
            )
        }
    }

    private fun saveAll() {
        if (classEntries.isEmpty()) {
            Toast.makeText(this, R.string.no_classes_to_save, Toast.LENGTH_SHORT).show()
            return
        }

        val updatedClasses = classEntries.map { entry ->
            val platform = if (entry.platformToggle.checkedButtonId == entry.btnNima.id)
                "NIMA" else "LMS"
            val enabled = entry.switchEnabled.isChecked

            entry.discoveredClass.copy(
                platform = platform,
                enabled = enabled
            )
        }

        // Save discovered classes (with user edits)
        ClassDiscoveryManager.saveDiscoveredClasses(this, updatedClasses)

        // Generate schedule.json
        ClassDiscoveryManager.generateScheduleJson(this, updatedClasses)

        Toast.makeText(this, R.string.schedule_saved, Toast.LENGTH_SHORT).show()

        // Tell the service to reload its schedule
        val intent = android.content.Intent(this, AutoJoinService::class.java).apply {
            putExtra("RELOAD_SCHEDULE", true)
        }
        startService(intent)

        finish()
    }
}