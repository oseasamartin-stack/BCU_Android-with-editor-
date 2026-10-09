package com.mandarin.bcu

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mandarin.bcu.androidutil.StaticStore
import com.mandarin.bcu.androidutil.io.AContext
import com.mandarin.bcu.androidutil.io.DefineItf
import common.CommonStatic
import common.battle.data.CustomEnemy
import common.pack.Source
import common.pack.UserProfile
import common.util.unit.Enemy

/**
 * [Editor] Basic stat editor for a custom enemy in an editable (workspace) pack.
 * Opened from Pack Management -> pack menu -> Edit enemies.
 */
class EnemyEditor : AppCompatActivity() {
    companion object {
        const val EXTRA_PACK = "editor_pack"
        const val EXTRA_INDEX = "editor_enemy_index"
    }

    private lateinit var enemy: Enemy
    private lateinit var ce: CustomEnemy

    private var changed = false
    private var textColor = 0

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { changed = true }
    }

    // Field references so we can read them back on save
    private lateinit var nameField: EditText
    private lateinit var hpField: EditText
    private lateinit var kbField: EditText
    private lateinit var speedField: EditText
    private lateinit var rangeField: EditText
    private lateinit var widthField: EditText
    private lateinit var tbaField: EditText
    private lateinit var dropField: EditText
    private val atkFields = ArrayList<EditText>()
    private val preFields = ArrayList<EditText>()
    private val areaBoxes = ArrayList<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = getSharedPreferences(StaticStore.CONFIG, Context.MODE_PRIVATE)

        if (!shared.getBoolean("theme", false)) {
            setTheme(R.style.AppTheme_night)
        } else {
            setTheme(R.style.AppTheme_day)
        }

        DefineItf.check(this)
        AContext.check()
        (CommonStatic.ctx as AContext).updateActivity(this)

        // Find the enemy we were asked to edit
        val packId = intent.getStringExtra(EXTRA_PACK)
        val index = intent.getIntExtra(EXTRA_INDEX, -1)

        val pack = if (packId == null) null else UserProfile.getUserPack(packId)
        val e = pack?.enemies?.getRaw(index)
        val data = e?.de

        if (pack == null || !pack.editable || e == null || data !is CustomEnemy) {
            StaticStore.showShortMessage(this, R.string.editor_enemy_missing)
            finish()
            return
        }

        enemy = e
        ce = data

        textColor = StaticStore.getAttributeColor(this, R.attr.TextPrimary)

        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmLeave()
            }
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(StaticStore.getAttributeColor(this, R.attr.backgroundPrimary))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))
        scroll.addView(root)

        val title = TextView(this)
        title.text = getString(R.string.editor_enemy_title)
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(textColor)
        root.addView(title)

        val idText = TextView(this)
        idText.text = "${enemy.id.pack} - ${StaticStore.trio(enemy.id.id)}"
        idText.setTextColor(textColor)
        idText.alpha = 0.7f
        root.addView(idText)

        header(root, R.string.editor_section_basic)

        nameField = field(root, R.string.editor_name, enemy.names.toString(), number = false)
        hpField = field(root, R.string.editor_hp, ce.hp.toString())
        kbField = field(root, R.string.editor_kb, ce.hb.toString())
        speedField = field(root, R.string.editor_speed, ce.speed.toString())
        rangeField = field(root, R.string.editor_range, ce.range.toString())
        widthField = field(root, R.string.editor_width, ce.width.toString())
        tbaField = field(root, R.string.editor_tba, ce.tba.toString())
        dropField = field(root, R.string.editor_drop, ce.drop.toString())

        header(root, R.string.editor_section_attacks)

        for (i in ce.atks.indices) {
            val atk = ce.atks[i]

            val label = TextView(this)
            label.text = getString(R.string.editor_attack_n).replace("_", (i + 1).toString())
            label.setTypeface(null, Typeface.BOLD)
            label.setTextColor(textColor)
            label.setPadding(0, dp(12), 0, 0)
            root.addView(label)

            atkFields.add(field(root, R.string.editor_damage, atk.atk.toString()))
            preFields.add(field(root, R.string.editor_pre, atk.pre.toString()))

            val area = CheckBox(this)
            area.text = getString(R.string.editor_area)
            area.setTextColor(textColor)
            area.isChecked = atk.range
            area.setOnCheckedChangeListener { _, _ -> changed = true }
            root.addView(area)
            areaBoxes.add(area)
        }

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.setPadding(0, dp(24), 0, 0)

        val cancel = Button(this)
        cancel.text = getString(R.string.main_file_cancel)
        cancel.setOnClickListener { confirmLeave() }

        val save = Button(this)
        save.text = getString(R.string.editor_save)
        save.setOnClickListener { save() }

        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        setContentView(scroll)
    }

    private fun header(root: LinearLayout, res: Int) {
        val h = TextView(this)
        h.text = getString(res)
        h.textSize = 18f
        h.setTypeface(null, Typeface.BOLD)
        h.setTextColor(StaticStore.getAttributeColor(this, R.attr.colorAccent))
        h.setPadding(0, dp(20), 0, dp(4))
        root.addView(h)
    }

    private fun field(root: LinearLayout, label: Int, value: String, number: Boolean = true): EditText {
        val l = TextView(this)
        l.text = getString(label)
        l.setTextColor(textColor)
        l.alpha = 0.8f
        l.setPadding(0, dp(8), 0, 0)
        root.addView(l)

        val et = EditText(this)
        et.setText(value)
        et.setTextColor(textColor)
        et.setSingleLine()
        et.inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        et.addTextChangedListener(watcher)
        root.addView(et)

        return et
    }

    /** Reads a whole number from a field; shows an error and returns null if invalid. */
    private fun readInt(et: EditText, min: Int): Int? {
        val v = et.text.toString().trim().toIntOrNull()

        if (v == null || v < min) {
            et.error = getString(R.string.editor_invalid_number).replace("_", min.toString())
            et.requestFocus()
            return null
        }

        return v
    }

    private fun save() {
        // Validate everything first so a bad field doesn't leave half the stats changed
        val hp = readInt(hpField, 1) ?: return
        val kb = readInt(kbField, 1) ?: return
        val speed = readInt(speedField, 0) ?: return
        val range = readInt(rangeField, 0) ?: return
        val width = readInt(widthField, 1) ?: return
        val tba = readInt(tbaField, 0) ?: return
        val drop = readInt(dropField, 0) ?: return

        val atks = ArrayList<Int>()
        val pres = ArrayList<Int>()

        for (i in atkFields.indices) {
            atks.add(readInt(atkFields[i], 0) ?: return)
            pres.add(readInt(preFields[i], 0) ?: return)
        }

        ce.hp = hp
        ce.hb = kb
        ce.speed = speed
        ce.range = range
        ce.width = width
        ce.tba = tba
        ce.drop = drop

        for (i in atks.indices) {
            ce.atks[i].atk = atks[i]
            ce.atks[i].pre = pres[i]
            ce.atks[i].range = areaBoxes[i].isChecked
        }

        ce.updateAllProc()

        val name = nameField.text.toString().trim()
        if (name.isNotEmpty())
            enemy.names.put(name)

        try {
            Source.Workspace.saveWorkspace()
        } catch (e: Exception) {
            Log.e("EnemyEditor", "Failed to save", e)
            StaticStore.showShortMessage(this, R.string.editor_save_fail)
            return
        }

        PackManagement.needReload = true
        changed = false

        StaticStore.showShortMessage(this, R.string.editor_saved)
        finish()
    }

    private fun confirmLeave() {
        if (!changed) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.editor_discard_title)
            .setMessage(R.string.editor_discard_msg)
            .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.main_file_cancel, null)
            .show()
    }
}
