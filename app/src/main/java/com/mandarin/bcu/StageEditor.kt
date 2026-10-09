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
import com.mandarin.bcu.androidutil.pack.EditorActions
import common.CommonStatic
import common.pack.Identifier
import common.pack.PackData
import common.pack.Source
import common.pack.UserProfile
import common.util.pack.Background
import common.util.stage.CastleImg
import common.util.stage.Limit
import common.util.stage.Music
import common.util.stage.SCDef
import common.util.stage.Stage
import common.util.unit.AbEnemy

/**
 * [Editor] Stage editor: basic stage settings and enemy spawn lines.
 * Opened from Pack Management -> pack menu -> Edit stages -> map -> stage.
 */
class StageEditor : AppCompatActivity() {
    companion object {
        const val EXTRA_PACK = "editor_pack"
        const val EXTRA_MAP = "editor_map"
        const val EXTRA_STAGE = "editor_stage"
    }

    private lateinit var pack: PackData.UserPack
    private lateinit var stage: Stage

    private var changed = false
    private var textColor = 0

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { changed = true }
    }

    private lateinit var nameField: EditText
    private lateinit var lenField: EditText
    private lateinit var hpField: EditText
    private lateinit var maxField: EditText
    private lateinit var noContBox: CheckBox
    private lateinit var bossGuardBox: CheckBox

    private lateinit var linesBox: LinearLayout

    // [Part 2] Looks & sound
    private var selBg: Identifier<Background>? = null
    private var selCastle: Identifier<CastleImg>? = null
    private var selMus0: Identifier<Music>? = null
    private var selMus1: Identifier<Music>? = null
    private lateinit var mushField: EditText

    // [Part 2] Limits
    private val rarityBoxes = ArrayList<CheckBox>()
    private lateinit var maxCatsField: EditText
    private lateinit var minCostField: EditText
    private lateinit var maxCostField: EditText
    private lateinit var firstRowBox: CheckBox

    /** One enemy spawn line in the UI. [original] keeps settings this screen doesn't show. */
    private inner class LineRow(var enemy: Identifier<AbEnemy>?, val original: SCDef.Line?) {
        lateinit var view: LinearLayout
        lateinit var enemyButton: Button
        lateinit var hpMult: EditText
        lateinit var atkMult: EditText
        lateinit var count: EditText
        lateinit var firstSpawn: EditText
        lateinit var respawnMin: EditText
        lateinit var respawnMax: EditText
        lateinit var baseHp: EditText
        lateinit var boss: CheckBox
    }

    private val rows = ArrayList<LineRow>()

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

        val packId = intent.getStringExtra(EXTRA_PACK)
        val mapIndex = intent.getIntExtra(EXTRA_MAP, -1)
        val stageIndex = intent.getIntExtra(EXTRA_STAGE, -1)

        val p = if (packId == null) null else UserProfile.getUserPack(packId)
        val sm = p?.mc?.maps?.getRaw(mapIndex)
        val st = sm?.list?.getRaw(stageIndex)

        if (p == null || !p.editable || st == null) {
            StaticStore.showShortMessage(this, R.string.editor_stage_missing)
            finish()
            return
        }

        pack = p
        stage = st

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
        title.text = getString(R.string.editor_stage_title)
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(textColor)
        root.addView(title)

        val idText = TextView(this)
        idText.text = stage.id.toString()
        idText.setTextColor(textColor)
        idText.alpha = 0.7f
        root.addView(idText)

        header(root, R.string.editor_section_stage)

        nameField = field(root, R.string.editor_name, stage.names.toString(), number = false)
        lenField = field(root, R.string.editor_stage_len, stage.len.toString())
        hpField = field(root, R.string.editor_stage_hp, stage.health.toString())
        maxField = field(root, R.string.editor_stage_max, stage.max.toString())
        noContBox = checkBox(root, R.string.editor_stage_nocont, stage.non_con)
        bossGuardBox = checkBox(root, R.string.editor_stage_bossguard, stage.bossGuard)

        buildLooks(root)
        buildLimits(root)

        header(root, R.string.editor_section_lines)

        val note = TextView(this)
        note.text = getString(R.string.editor_lines_note)
        note.setTextColor(textColor)
        note.alpha = 0.7f
        root.addView(note)

        linesBox = LinearLayout(this)
        linesBox.orientation = LinearLayout.VERTICAL
        root.addView(linesBox)

        for (line in stage.data.datas) {
            if (line == null) continue
            addRow(LineRow(line.enemy, line))
        }

        val add = Button(this)
        add.text = getString(R.string.editor_add_line)
        add.setOnClickListener {
            EditorActions.pickEnemy(this, pack) { e ->
                changed = true
                addRow(LineRow(e.id, null))
            }
        }
        root.addView(add)

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

        val delete = Button(this)
        delete.text = getString(R.string.editor_delete_stage)
        delete.setOnClickListener {
            EditorActions.confirm(this, R.string.editor_delete_stage_title, R.string.editor_delete_stage_msg) {
                stage.getCont().list.remove(stage)

                try {
                    Source.Workspace.saveWorkspace()
                } catch (e: Exception) {
                    Log.e("StageEditor", "Failed to save", e)
                }

                PackManagement.needReload = true
                StaticStore.showShortMessage(this, R.string.editor_deleted)
                finish()
            }
        }
        root.addView(delete)

        setContentView(scroll)
    }

    private fun pickerButton(root: LinearLayout, label: Int, text: String, onClick: (Button) -> Unit): Button {
        val l = TextView(this)
        l.text = getString(label)
        l.setTextColor(textColor)
        l.alpha = 0.8f
        l.setPadding(0, dp(8), 0, 0)
        root.addView(l)

        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick(b) }
        root.addView(b)
        return b
    }

    private fun buildLooks(root: LinearLayout) {
        header(root, R.string.editor_section_looks)

        selBg = stage.bg
        selCastle = stage.castle
        selMus0 = stage.mus0
        selMus1 = stage.mus1

        pickerButton(root, R.string.editor_stage_bg, EditorActions.bgLabel(this, selBg)) { b ->
            EditorActions.pickBackground(this, pack) { id ->
                changed = true
                selBg = id
                b.text = EditorActions.bgLabel(this, id)
            }
        }

        pickerButton(root, R.string.editor_stage_castle, EditorActions.castleLabel(this, selCastle)) { b ->
            EditorActions.pickCastle(this, stage) { id ->
                changed = true
                selCastle = id
                b.text = EditorActions.castleLabel(this, id)
            }
        }

        pickerButton(root, R.string.editor_stage_music, EditorActions.musicLabel(this, selMus0)) { b ->
            EditorActions.pickMusic(this, pack) { id ->
                changed = true
                selMus0 = id
                b.text = EditorActions.musicLabel(this, id)
            }
        }

        pickerButton(root, R.string.editor_stage_music2, EditorActions.musicLabel(this, selMus1)) { b ->
            EditorActions.pickMusic(this, pack) { id ->
                changed = true
                selMus1 = id
                b.text = EditorActions.musicLabel(this, id)
            }
        }

        mushField = field(root, R.string.editor_stage_mush, stage.mush.toString())
    }

    private fun buildLimits(root: LinearLayout) {
        header(root, R.string.editor_section_limits)

        val lim = stage.lim ?: Limit()

        val note = TextView(this)
        note.text = getString(R.string.editor_limits_note)
        note.setTextColor(textColor)
        note.alpha = 0.7f
        root.addView(note)

        val rarities = listOf(R.string.editor_rar_normal, R.string.editor_rar_special, R.string.editor_rar_rare,
            R.string.editor_rar_super, R.string.editor_rar_uber, R.string.editor_rar_legend)

        for (i in rarities.indices) {
            // rare == 0 means every rarity is allowed
            val allowed = lim.rare == 0 || ((lim.rare shr i) and 1) == 1
            rarityBoxes.add(checkBox(root, rarities[i], allowed))
        }

        maxCatsField = field(root, R.string.editor_lim_num, lim.num.toString())
        minCostField = field(root, R.string.editor_lim_min, lim.min.toString())
        maxCostField = field(root, R.string.editor_lim_max, lim.max.toString())
        firstRowBox = checkBox(root, R.string.editor_lim_line, lim.line == 1)
    }

    private fun addRow(row: LineRow) {
        val o = row.original

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(12), dp(12), dp(12))
        card.setBackgroundResource(R.drawable.cell_shape)

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, dp(8))

        row.view = card

        row.enemyButton = Button(this)
        row.enemyButton.text = EditorActions.enemyLabel(row.enemy)
        row.enemyButton.isAllCaps = false
        row.enemyButton.setOnClickListener {
            EditorActions.pickEnemy(this, pack) { e ->
                changed = true
                row.enemy = e.id
                row.enemyButton.text = EditorActions.enemyLabel(e.id)
            }
        }
        card.addView(row.enemyButton)

        row.hpMult = field(card, R.string.editor_line_hp, (o?.multiple ?: 100).toString())
        row.atkMult = field(card, R.string.editor_line_atk, (o?.mult_atk ?: 100).toString())
        row.count = field(card, R.string.editor_line_count, (o?.number ?: 0).toString())
        row.firstSpawn = field(card, R.string.editor_line_first, (o?.spawn_0 ?: 0).toString())
        row.respawnMin = field(card, R.string.editor_line_respawn_min, (o?.respawn_0 ?: 60).toString())
        row.respawnMax = field(card, R.string.editor_line_respawn_max, (o?.respawn_1 ?: 60).toString())
        row.baseHp = field(card, R.string.editor_line_basehp, (o?.castle_0 ?: 100).toString())
        row.boss = checkBox(card, R.string.editor_line_boss, (o?.boss ?: 0) > 0)

        val remove = Button(this)
        remove.text = getString(R.string.editor_line_remove)
        remove.setOnClickListener {
            changed = true
            rows.remove(row)
            linesBox.removeView(card)
        }
        card.addView(remove)

        rows.add(row)
        linesBox.addView(card, lp)
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

    private fun checkBox(root: LinearLayout, label: Int, value: Boolean): CheckBox {
        val box = CheckBox(this)
        box.text = getString(label)
        box.setTextColor(textColor)
        box.isChecked = value
        box.setOnCheckedChangeListener { _, _ -> changed = true }
        root.addView(box)
        return box
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

    private fun readInt(et: EditText, min: Int, max: Int = Int.MAX_VALUE): Int? {
        val v = et.text.toString().trim().toIntOrNull()

        if (v == null || v < min) {
            et.error = getString(R.string.editor_invalid_number).replace("_", min.toString())
            et.requestFocus()
            return null
        }

        if (v > max) {
            et.error = getString(R.string.editor_invalid_max).replace("_", max.toString())
            et.requestFocus()
            return null
        }

        return v
    }

    private fun save() {
        val len = readInt(lenField, 1000) ?: return
        val hp = readInt(hpField, 1) ?: return
        val max = readInt(maxField, 1, 50) ?: return

        val mush = readInt(mushField, 0, 100) ?: return
        val maxCats = readInt(maxCatsField, 0, 50) ?: return
        val minCost = readInt(minCostField, 0) ?: return
        val maxCost = readInt(maxCostField, 0) ?: return

        var rare = 0
        for (i in rarityBoxes.indices)
            if (rarityBoxes[i].isChecked)
                rare = rare or (1 shl i)

        if (rare == 0) {
            StaticStore.showShortMessage(this, R.string.editor_lim_no_rarity)
            return
        }

        // All rarities allowed is stored as 0 (no restriction)
        if (rare == (1 shl rarityBoxes.size) - 1)
            rare = 0

        val lines = ArrayList<SCDef.Line>()

        for (row in rows) {
            val enemy = row.enemy

            if (enemy == null) {
                StaticStore.showShortMessage(this, R.string.editor_line_no_enemy)
                return
            }

            val hpMult = readInt(row.hpMult, 1) ?: return
            val atkMult = readInt(row.atkMult, 1) ?: return
            val count = readInt(row.count, 0) ?: return
            val first = readInt(row.firstSpawn, 0) ?: return
            val rMin = readInt(row.respawnMin, 0) ?: return
            val rMax = readInt(row.respawnMax, 0) ?: return
            val base = readInt(row.baseHp, 0, 100) ?: return

            // Start from the original line so settings not shown here are kept
            val line = row.original?.clone() ?: newLine()

            line.enemy = enemy
            line.multiple = hpMult
            line.mult_atk = atkMult
            line.number = count
            line.spawn_0 = first
            if (row.original == null || line.spawn_1 < first) line.spawn_1 = first
            line.respawn_0 = minOf(rMin, rMax)
            line.respawn_1 = maxOf(rMin, rMax)
            line.castle_0 = base
            line.boss = if (row.boss.isChecked) maxOf(line.boss, 1) else 0

            lines.add(line)
        }

        stage.len = len
        stage.health = hp
        stage.max = max
        stage.non_con = noContBox.isChecked
        stage.bossGuard = bossGuardBox.isChecked
        stage.data.datas = lines.toTypedArray()

        stage.bg = selBg
        stage.castle = selCastle
        stage.mus0 = selMus0
        stage.mus1 = selMus1
        stage.mush = mush

        val lim = stage.lim ?: Limit()
        lim.rare = rare
        lim.num = maxCats
        lim.min = minCost
        lim.max = maxCost
        lim.line = if (firstRowBox.isChecked) 1 else 0
        stage.lim = lim

        val name = nameField.text.toString().trim()
        if (name.isNotEmpty())
            stage.names.put(name)

        try {
            Source.Workspace.saveWorkspace()
        } catch (e: Exception) {
            Log.e("StageEditor", "Failed to save", e)
            StaticStore.showShortMessage(this, R.string.editor_save_fail)
            return
        }

        PackManagement.needReload = true
        changed = false

        StaticStore.showShortMessage(this, R.string.editor_saved)
        finish()
    }

    /** Defaults for a brand-new spawn line. */
    private fun newLine(): SCDef.Line {
        val line = SCDef.Line()
        line.multiple = 100
        line.mult_atk = 100
        line.castle_0 = 100
        line.castle_1 = 0
        line.layer_0 = 0
        line.layer_1 = 9
        return line
    }

    private fun confirmLeave() {
        if (!changed) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.editor_discard_title)
            .setMessage(R.string.editor_discard_stage_msg)
            .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.main_file_cancel, null)
            .show()
    }
}
