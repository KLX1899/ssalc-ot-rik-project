// ManageClassesActivity.kt
package com.example.myapplication

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.util.Calendar

class ManageClassesActivity : AppCompatActivity() {

    private lateinit var classListContainer: LinearLayout
    private lateinit var tvClassCount: TextView
    private val classEntries = mutableListOf<ClassEntryHolder>()

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
            v.setPadding(0, sys.top, 0, sys.bottom)
            insets
        }

        classListContainer = findViewById(R.id.classListContainer)
        tvClassCount = findViewById(R.id.tvClassCount)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
        
        // Save and Apply Schedule
        findViewById<MaterialButton>(R.id.btnSaveSchedule).setOnClickListener {
            syncUIStateToDisk()
            generateAndApplySchedule()
            Toast.makeText(this, R.string.schedule_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        
        // Add Manual Class
        findViewById<MaterialButton>(R.id.btnAddManualClass).setOnClickListener {
            syncUIStateToDisk() // Save any toggle changes before opening dialog
            showClassDialog(null, -1)
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

        classes.forEachIndexed { index, cls ->
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_class_card, classListContainer, false)

            val tvName = card.findViewById<TextView>(R.id.tvClassName)
            val tvSchedule = card.findViewById<TextView>(R.id.tvClassSchedule)
            val toggle = card.findViewById<MaterialButtonToggleGroup>(R.id.platformToggle)
            val btnLms = card.findViewById<MaterialButton>(R.id.btnLms)
            val btnNima = card.findViewById<MaterialButton>(R.id.btnNima)
            val switchEnabled = card.findViewById<SwitchMaterial>(R.id.switchEnabled)
            val btnEditClass = card.findViewById<ImageButton>(R.id.btnEditClass)

            tvName.text = cls.name

            val scheduleLines = cls.days.map { day ->
                "${dayDisplayMap[day] ?: day}  ${cls.start} - ${cls.end}"
            }
            tvSchedule.text = scheduleLines.joinToString("\n")

            if (cls.platform.uppercase() == "NIMA") {
                toggle.check(btnNima.id)
            } else {
                toggle.check(btnLms.id)
            }

            switchEnabled.isChecked = cls.enabled

            // Edit button click listener
            btnEditClass.setOnClickListener {
                syncUIStateToDisk() // Save toggles of other classes before modifying this one
                showClassDialog(cls, index)
            }

            classListContainer.addView(card)

            classEntries.add(
                ClassEntryHolder(cls, toggle, btnLms, btnNima, switchEnabled)
            )
        }
    }

    // Handles BOTH adding a new class and editing an existing one
    private fun showClassDialog(existingClass: ClassDiscoveryManager.DiscoveredClass?, index: Int) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_class, null)
        
        val etCourseName = dialogView.findViewById<TextInputEditText>(R.id.etCourseName)
        val platformToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.platformToggleDialog)
        val btnLms = dialogView.findViewById<MaterialButton>(R.id.btnLmsDialog)
        val btnNima = dialogView.findViewById<MaterialButton>(R.id.btnNimaDialog)
        val btnStartTime = dialogView.findViewById<MaterialButton>(R.id.btnStartTime)
        val btnEndTime = dialogView.findViewById<MaterialButton>(R.id.btnEndTime)

        val checkboxMap = mapOf(
            "sat" to dialogView.findViewById<CheckBox>(R.id.cbSat),
            "sun" to dialogView.findViewById<CheckBox>(R.id.cbSun),
            "mon" to dialogView.findViewById<CheckBox>(R.id.cbMon),
            "tue" to dialogView.findViewById<CheckBox>(R.id.cbTue),
            "wed" to dialogView.findViewById<CheckBox>(R.id.cbWed),
            "thu" to dialogView.findViewById<CheckBox>(R.id.cbThu),
            "fri" to dialogView.findViewById<CheckBox>(R.id.cbFri)
        )

        var startTimeStr = ""
        var endTimeStr = ""

        // Pre-fill data if editing an existing class
        if (existingClass != null) {
            etCourseName.setText(existingClass.name)
            startTimeStr = existingClass.start
            endTimeStr = existingClass.end
            btnStartTime.text = "شروع: $startTimeStr"
            btnEndTime.text = "پایان: $endTimeStr"
            
            if (existingClass.platform.uppercase() == "NIMA") {
                platformToggle.check(btnNima.id)
            } else {
                platformToggle.check(btnLms.id)
            }

            existingClass.days.forEach { day ->
                checkboxMap[day]?.isChecked = true
            }
        } else {
            platformToggle.check(btnLms.id) // Default for new class
        }

        btnStartTime.setOnClickListener {
            showTimePicker { formattedTime ->
                startTimeStr = formattedTime
                btnStartTime.text = "شروع: $formattedTime"
            }
        }

        btnEndTime.setOnClickListener {
            showTimePicker { formattedTime ->
                endTimeStr = formattedTime
                btnEndTime.text = "پایان: $formattedTime"
            }
        }

        val dialogBuilder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (existingClass == null) "افزودن کلاس جدید" else "ویرایش کلاس")
            .setView(dialogView)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { dialog, _ ->
                val name = etCourseName.text.toString().trim()
                val selectedDays = checkboxMap.filterValues { it.isChecked }.keys.toList()

                if (name.isEmpty()) {
                    Toast.makeText(this, "لطفاً نام کلاس را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (startTimeStr.isEmpty() || endTimeStr.isEmpty()) {
                    Toast.makeText(this, "ساعت شروع و پایان کلاس را تنظیم کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (selectedDays.isEmpty()) {
                    Toast.makeText(this, "حداقل یک روز برگزاری را مشخص کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val chosenPlatform = if (platformToggle.checkedButtonId == btnNima.id) "NIMA" else "LMS"

                val updatedClass = ClassDiscoveryManager.DiscoveredClass(
                    name = name,
                    url = existingClass?.url ?: "", // Keep URL if it was scraped
                    days = selectedDays,
                    start = startTimeStr,
                    end = endTimeStr,
                    platform = chosenPlatform,
                    enabled = existingClass?.enabled ?: true // Preserve enabled state
                )

                val currentClasses = ClassDiscoveryManager.loadDiscoveredClasses(this).toMutableList()
                
                if (existingClass != null && index != -1) {
                    currentClasses[index] = updatedClass
                    Toast.makeText(this, "کلاس ویرایش شد", Toast.LENGTH_SHORT).show()
                } else {
                    currentClasses.add(updatedClass)
                    Toast.makeText(this, "کلاس اضافه شد", Toast.LENGTH_SHORT).show()
                }

                ClassDiscoveryManager.saveDiscoveredClasses(this, currentClasses)
                loadAndDisplay()
                dialog.dismiss()
            }

        // Add Delete button if editing
        if (existingClass != null) {
            dialogBuilder.setNeutralButton("حذف") { _, _ ->
                val currentClasses = ClassDiscoveryManager.loadDiscoveredClasses(this).toMutableList()
                if (index in currentClasses.indices) {
                    currentClasses.removeAt(index)
                    ClassDiscoveryManager.saveDiscoveredClasses(this, currentClasses)
                    Toast.makeText(this, "کلاس حذف شد", Toast.LENGTH_SHORT).show()
                    loadAndDisplay()
                }
            }
        }

        val dialog = dialogBuilder.create()
        dialog.show()

        // Make the delete button red for emphasis
        if (existingClass != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(android.graphics.Color.parseColor("#EF9A9A"))
        }
    }

    private fun showTimePicker(onTimeSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        TimePickerDialog(this, { _, hour, minute ->
            onTimeSelected("%02d:%02d".format(hour, minute))
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
    }

    // Safely writes current toggle/switch states to the local JSON file
    private fun syncUIStateToDisk() {
        if (classEntries.isEmpty()) return

        val updatedClasses = classEntries.map { entry ->
            val platform = if (entry.platformToggle.checkedButtonId == entry.btnNima.id) "NIMA" else "LMS"
            val enabled = entry.switchEnabled.isChecked

            entry.discoveredClass.copy(
                platform = platform,
                enabled = enabled
            )
        }
        ClassDiscoveryManager.saveDiscoveredClasses(this, updatedClasses)
    }

    // Rebuilds the final schedule JSON and tells background service to reload
    private fun generateAndApplySchedule() {
        val currentClasses = ClassDiscoveryManager.loadDiscoveredClasses(this)
        ClassDiscoveryManager.generateScheduleJson(this, currentClasses)

        val intent = android.content.Intent(this, AutoJoinService::class.java).apply {
            putExtra("RELOAD_SCHEDULE", true)
        }
        startService(intent)
    }
}
