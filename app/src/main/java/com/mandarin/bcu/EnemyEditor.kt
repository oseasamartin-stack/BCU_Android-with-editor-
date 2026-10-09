package com.mandarin.bcu

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
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
import com.mandarin.bcu.androidutil.pack.EditorActions
import common.battle.data.AtkDataModel
import common.battle.data.CustomEnemy
import common.battle.data.CustomEntity
import common.battle.data.CustomUnit
import common.battle.data.PCoin
import common.util.lang.MultiLangData
import common.util.unit.Form
import common.pack.Source
import common.pack.UserProfile
import common.util.Data
import common.util.Data.Proc
import common.util.unit.Trait
import common.util.anim.AnimCE
import common.util.unit.Enemy
import common.util.unit.EneRand

/**
 * [Editor] Stat editor for a custom enemy, or for one form of a custom unit, in an editable (workspace) pack.
 * Enemies: Pack Management -> pack menu -> Edit enemies.
 * Units:   Pack Management -> pack menu -> Edit units -> unit -> form (pass EXTRA_UNIT + EXTRA_FORM).
 */
class EnemyEditor : AppCompatActivity() {
    companion object {
        const val EXTRA_PACK = "editor_pack"
        const val EXTRA_INDEX = "editor_enemy_index"
        const val EXTRA_UNIT = "editor_unit_index"
        const val EXTRA_FORM = "editor_form_index"
    }

    // Exactly one of these is set
    private var enemyOrNull: Enemy? = null
    private var form: Form? = null

    private val enemy: Enemy get() = enemyOrNull!!
    private val isUnit get() = form != null
    private val names: MultiLangData get() = form?.names ?: enemy.names

    private lateinit var ce: CustomEntity

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
    private var dropField: EditText? = null
    private var priceField: EditText? = null
    private var respField: EditText? = null
    // [Editor] One attack in the UI. [model] is null for attacks added on this screen.
    private inner class AtkRow(val model: AtkDataModel?) {
        lateinit var view: LinearLayout
        lateinit var label: TextView
        lateinit var damage: EditText
        lateinit var pre: EditText
        lateinit var area: CheckBox
        lateinit var ld0: EditText
        lateinit var ld1: EditText
    }

    private val atkRows = ArrayList<AtkRow>()
    private lateinit var atkBox: LinearLayout

    // [Editor part 2] Traits and abilities
    private lateinit var procs: Proc
    private val traitBoxes = LinkedHashMap<Trait, CheckBox>()
    private val abilities = ArrayList<AbilityRow>()

    // [Units] On/off cat abilities stored as bit flags in ce.abi
    private val flagBoxes = LinkedHashMap<Int, CheckBox>()

    // [Units] Talents. Each talent is 14 numbers: type, max level, 4 x (level 1 value, max value), then extras.
    private inner class TalentRow(val data: IntArray) {
        lateinit var view: LinearLayout
        lateinit var title: TextView
        lateinit var maxLv: EditText
        val lo = ArrayList<EditText>()
        val hi = ArrayList<EditText>()
        lateinit var superBox: CheckBox
    }

    private val talentRows = ArrayList<TalentRow>()
    private lateinit var talentBox: LinearLayout

    /** One ability: a checkbox, its number fields, and how to write them back. */
    private class AbilityRow(
        val box: CheckBox,
        val fields: List<EditText>,
        val mins: List<Int>,
        val maxs: List<Int>,
        val apply: (Boolean, List<Int>) -> Unit
    )

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

        if (intent.hasExtra(EXTRA_UNIT)) {
            val u = pack?.units?.getRaw(intent.getIntExtra(EXTRA_UNIT, -1))
            val fi = intent.getIntExtra(EXTRA_FORM, -1)
            val f = if (u != null && fi >= 0 && fi < u.forms.size) u.forms[fi] else null
            val data = f?.du

            if (pack == null || !pack.editable || f == null || data !is CustomUnit) {
                StaticStore.showShortMessage(this, R.string.editor_enemy_missing)
                finish()
                return
            }

            form = f
            ce = data
        } else {
            val e = pack?.enemies?.getRaw(index)
            val data = e?.de

            if (pack == null || !pack.editable || e == null || data !is CustomEnemy) {
                StaticStore.showShortMessage(this, R.string.editor_enemy_missing)
                finish()
                return
            }

            enemyOrNull = e
            ce = data
        }

        // Work on a copy of the abilities; only written back on Save
        ce.updateAllProc()
        procs = ce.allProc.clone()

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
        title.text = getString(if (isUnit) R.string.editor_form_title else R.string.editor_enemy_title)
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(textColor)
        root.addView(title)

        val idText = TextView(this)
        val f = form
        idText.text = if (f != null)
            "${f.unit.id.pack} - ${StaticStore.trio(f.unit.id.id)} - " + getString(R.string.editor_form_n).replace("_", (f.fid + 1).toString())
        else
            "${enemy.id.pack} - ${StaticStore.trio(enemy.id.id)}"
        idText.setTextColor(textColor)
        idText.alpha = 0.7f
        root.addView(idText)

        header(root, R.string.editor_section_basic)

        nameField = field(root, R.string.editor_name, names.toString(), number = false)
        hpField = field(root, R.string.editor_hp, ce.hp.toString())
        kbField = field(root, R.string.editor_kb, ce.hb.toString())
        speedField = field(root, R.string.editor_speed, ce.speed.toString())
        rangeField = field(root, R.string.editor_range, ce.range.toString())
        widthField = field(root, R.string.editor_width, ce.width.toString())
        tbaField = field(root, R.string.editor_tba, ce.tba.toString())
        val cu = ce as? CustomUnit
        val cen = ce as? CustomEnemy

        if (cu != null) {
            priceField = field(root, R.string.editor_price, cu.price.toString())
            respField = field(root, R.string.editor_resp, cu.resp.toString())
        } else if (cen != null) {
            dropField = field(root, R.string.editor_drop, cen.drop.toString())
        }

        header(root, R.string.editor_section_attacks)

        val ldNote = TextView(this)
        ldNote.text = getString(R.string.editor_ld_note)
        ldNote.setTextColor(textColor)
        ldNote.alpha = 0.7f
        root.addView(ldNote)

        atkBox = LinearLayout(this)
        atkBox.orientation = LinearLayout.VERTICAL
        root.addView(atkBox)

        for (atk in ce.atks)
            addAtkRow(AtkRow(atk))

        val addAtk = Button(this)
        addAtk.text = getString(R.string.editor_add_attack)
        addAtk.setOnClickListener {
            changed = true
            addAtkRow(AtkRow(null))
        }
        root.addView(addAtk)

        buildTraits(root)
        buildAbilities(root)

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

        if (!isUnit) {
            val delete = Button(this)
            delete.text = getString(R.string.editor_delete_enemy)
            delete.setOnClickListener { deleteEnemy() }
            root.addView(delete)
        }

        setContentView(scroll)
    }

    private fun addAtkRow(row: AtkRow) {
        val m = row.model

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(8), dp(12), dp(8))
        card.setBackgroundResource(R.drawable.cell_shape)

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, dp(8))

        row.view = card

        row.label = TextView(this)
        row.label.setTypeface(null, Typeface.BOLD)
        row.label.setTextColor(textColor)
        card.addView(row.label)

        row.damage = field(card, R.string.editor_damage, (m?.atk ?: 0).toString())
        row.pre = field(card, R.string.editor_pre, (m?.pre ?: 1).toString())

        row.area = CheckBox(this)
        row.area.text = getString(R.string.editor_area)
        row.area.setTextColor(textColor)
        row.area.isChecked = m?.range ?: true
        row.area.setOnCheckedChangeListener { _, _ -> changed = true }
        card.addView(row.area)

        row.ld0 = field(card, R.string.editor_ld0, (m?.ld0 ?: 0).toString(), signed = true)
        row.ld1 = field(card, R.string.editor_ld1, (m?.ld1 ?: 0).toString(), signed = true)

        val remove = Button(this)
        remove.text = getString(R.string.editor_remove_attack)
        remove.setOnClickListener {
            if (atkRows.size <= 1) {
                StaticStore.showShortMessage(this, R.string.editor_need_attack)
                return@setOnClickListener
            }

            changed = true
            atkRows.remove(row)
            atkBox.removeView(card)
            renumberAttacks()
        }
        card.addView(remove)

        atkRows.add(row)
        atkBox.addView(card, lp)
        renumberAttacks()
    }

    private fun renumberAttacks() {
        for (i in atkRows.indices)
            atkRows[i].label.text = getString(R.string.editor_attack_n).replace("_", (i + 1).toString())
    }

    /** Remove this enemy from the pack (and from any of the pack's stages that spawn it). */
    private fun deleteEnemy() {
        val pack = UserProfile.getUserPack(enemy.id.pack) ?: return

        fun isThis(line: common.util.stage.SCDef.Line?): Boolean {
            val id = line?.enemy ?: return false
            return id.cls != EneRand::class.java && id.pack == enemy.id.pack && id.id == enemy.id.id
        }

        var used = 0
        for (sm in pack.mc.maps.list.filterNotNull())
            for (st in sm.list.list.filterNotNull())
                if (st.data.datas.any { isThis(it) })
                    used++

        val msg = if (used == 0)
            getString(R.string.editor_delete_enemy_msg)
        else
            getString(R.string.editor_delete_enemy_used).replace("_", used.toString())

        AlertDialog.Builder(this)
            .setTitle(R.string.editor_delete_enemy_title)
            .setMessage(msg)
            .setPositiveButton(R.string.editor_delete) { _, _ ->
                // Take it out of any stages first so they don't point at a missing enemy
                for (sm in pack.mc.maps.list.filterNotNull())
                    for (st in sm.list.list.filterNotNull())
                        st.data.datas = st.data.datas.filter { !isThis(it) }.toTypedArray()

                pack.enemies.remove(enemy)

                // Delete its animation folder if no other enemy in the pack uses it
                val anim = enemy.anim as? AnimCE
                if (anim != null && anim.id.pack == pack.sid &&
                    pack.enemies.list.filterNotNull().none { (it.anim as? AnimCE)?.id?.toString() == anim.id.toString() }) {
                    try {
                        CommonStatic.ctx.getWorkspaceFile(anim.id.getPath()).deleteRecursively()
                    } catch (e: Exception) {
                        Log.e("EnemyEditor", "Failed to delete animation", e)
                    }
                }

                try {
                    Source.Workspace.saveWorkspace()
                } catch (e: Exception) {
                    Log.e("EnemyEditor", "Failed to save", e)
                }

                PackManagement.needReload = true
                changed = false
                StaticStore.showShortMessage(this, R.string.editor_deleted)
                finish()
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .show()
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

    private fun field(root: LinearLayout, label: Int, value: String, number: Boolean = true, signed: Boolean = false, labelText: String? = null): EditText {
        val l = TextView(this)
        l.text = labelText ?: getString(label)
        l.setTextColor(textColor)
        l.alpha = 0.8f
        l.setPadding(0, dp(8), 0, 0)
        root.addView(l)

        val et = EditText(this)
        et.setText(value)
        et.setTextColor(textColor)
        et.setSingleLine()
        et.inputType = when {
            !number -> InputType.TYPE_CLASS_TEXT
            signed -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            else -> InputType.TYPE_CLASS_NUMBER
        }
        et.addTextChangedListener(watcher)
        root.addView(et)

        return et
    }

    /** Reads a whole number from a field; shows an error and returns null if invalid. */
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

    private fun buildTraits(root: LinearLayout) {
        header(root, if (isUnit) R.string.editor_section_targets else R.string.editor_section_traits)

        val list = listOf(
            Data.TRAIT_RED to R.string.editor_trait_red,
            Data.TRAIT_FLOAT to R.string.editor_trait_float,
            Data.TRAIT_BLACK to R.string.editor_trait_black,
            Data.TRAIT_METAL to R.string.editor_trait_metal,
            Data.TRAIT_ANGEL to R.string.editor_trait_angel,
            Data.TRAIT_ALIEN to R.string.editor_trait_alien,
            Data.TRAIT_ZOMBIE to R.string.editor_trait_zombie,
            Data.TRAIT_DEMON to R.string.editor_trait_aku,
            Data.TRAIT_RELIC to R.string.editor_trait_relic,
            Data.TRAIT_WHITE to R.string.editor_trait_white
        )

        val bcTraits = UserProfile.getBCData().traits

        for ((id, label) in list) {
            val trait = bcTraits.get(id.toInt()) ?: continue

            val box = CheckBox(this)
            box.text = getString(label)
            box.setTextColor(textColor)
            box.isChecked = ce.traits.contains(trait)
            box.setOnCheckedChangeListener { _, _ -> changed = true }
            root.addView(box)

            traitBoxes[trait] = box
        }
    }

    private fun buildAbilities(root: LinearLayout) {
        header(root, R.string.editor_section_abilities)

        val note = TextView(this)
        note.text = getString(R.string.editor_abilities_note)
        note.setTextColor(textColor)
        note.alpha = 0.7f
        root.addView(note)

        val p = procs

        val atkGroup = collapsible(root, R.string.editor_group_attack, true)
        val statusGroup = collapsible(root, R.string.editor_group_status, false)
        val defGroup = collapsible(root, R.string.editor_group_defense, false)
        val imuGroup = collapsible(root, R.string.editor_group_immune, false)

        if (isUnit) {
            buildCatAbilities(collapsible(root, R.string.editor_group_cat, true), p)
            buildTalents(collapsible(root, R.string.editor_group_talents, false))
        }

        // Values shown when an ability is off (sensible starting points)
        fun orDef(v: Int, def: Int) = if (v != 0) v else def

        ability(atkGroup, R.string.editor_ab_kb, p.KB.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.KB.prob, 100), 1, 100))) { on, v ->
            p.KB.prob = if (on) v[0] else 0
            if (!on) { p.KB.dis = 0; p.KB.time = 0 }
        }

        ability(atkGroup, R.string.editor_ab_freeze, p.STOP.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.STOP.prob, 100), 1, 100),
                Spec(R.string.editor_duration, orDef(p.STOP.time, 30), 1))) { on, v ->
            p.STOP.prob = if (on) v[0] else 0
            p.STOP.time = if (on) v[1] else 0
        }

        ability(atkGroup, R.string.editor_ab_slow, p.SLOW.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.SLOW.prob, 100), 1, 100),
                Spec(R.string.editor_duration, orDef(p.SLOW.time, 60), 1))) { on, v ->
            p.SLOW.prob = if (on) v[0] else 0
            p.SLOW.time = if (on) v[1] else 0
        }

        ability(atkGroup, R.string.editor_ab_weaken, p.WEAK.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.WEAK.prob, 100), 1, 100),
                Spec(R.string.editor_duration, orDef(p.WEAK.time, 60), 1),
                Spec(R.string.editor_weak_mult, orDef(p.WEAK.mult, 50), 0, 1000))) { on, v ->
            p.WEAK.prob = if (on) v[0] else 0
            p.WEAK.time = if (on) v[1] else 0
            p.WEAK.mult = if (on) v[2] else 0
        }

        ability(atkGroup, R.string.editor_ab_crit, p.CRIT.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.CRIT.prob, 50), 1, 100))) { on, v ->
            p.CRIT.prob = if (on) v[0] else 0
            if (!on) p.CRIT.mult = 0
        }

        ability(atkGroup, R.string.editor_ab_wave, p.WAVE.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.WAVE.prob, 100), 1, 100),
                Spec(R.string.editor_level, orDef(p.WAVE.lv, 1), 1, 20))) { on, v ->
            p.WAVE.prob = if (on) v[0] else 0
            p.WAVE.lv = if (on) v[1] else 0
        }

        ability(atkGroup, R.string.editor_ab_surge, p.VOLC.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.VOLC.prob, 100), 1, 100),
                Spec(R.string.editor_surge_min, orDef(p.VOLC.dis_0, 200), 0),
                Spec(R.string.editor_surge_max, orDef(p.VOLC.dis_1, 400), 0),
                Spec(R.string.editor_level, orDef(p.VOLC.time / Data.VOLC_ITV, 1), 1, 20))) { on, v ->
            p.VOLC.prob = if (on) v[0] else 0
            p.VOLC.dis_0 = if (on) minOf(v[1], v[2]) else 0
            p.VOLC.dis_1 = if (on) maxOf(v[1], v[2]) else 0
            p.VOLC.time = if (on) v[3] * Data.VOLC_ITV else 0
        }

        ability(defGroup, R.string.editor_ab_barrier, p.BARRIER.health > 0,
            listOf(Spec(R.string.editor_barrier_hp, orDef(p.BARRIER.health, 10000), 1))) { on, v ->
            p.BARRIER.health = if (on) v[0] else 0
            if (!on) { p.BARRIER.regentime = 0; p.BARRIER.timeout = 0 }
        }
            // ---- Status effects ----
        ability(statusGroup, R.string.editor_ab_toxic, p.POIATK.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.POIATK.prob, 100), 1, 100),
                Spec(R.string.editor_toxic_pct, orDef(p.POIATK.mult, 10), 1, 100))) { on, v ->
            p.POIATK.prob = if (on) v[0] else 0
            p.POIATK.mult = if (on) v[1] else 0
        }

        ability(statusGroup, R.string.editor_ab_curse, p.CURSE.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.CURSE.prob, 100), 1, 100),
                Spec(R.string.editor_duration, orDef(p.CURSE.time, 60), 1))) { on, v ->
            p.CURSE.prob = if (on) v[0] else 0
            p.CURSE.time = if (on) v[1] else 0
        }

        ability(statusGroup, R.string.editor_ab_seal, p.SEAL.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.SEAL.prob, 100), 1, 100),
                Spec(R.string.editor_duration, orDef(p.SEAL.time, 60), 1))) { on, v ->
            p.SEAL.prob = if (on) v[0] else 0
            p.SEAL.time = if (on) v[1] else 0
        }

        ability(statusGroup, R.string.editor_ab_warp, p.WARP.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.WARP.prob, 100), 1, 100),
                Spec(R.string.editor_duration, orDef(p.WARP.time, 30), 1),
                Spec(R.string.editor_warp_min, orDef(p.WARP.dis_0, 300), -10000, 10000),
                Spec(R.string.editor_warp_max, orDef(p.WARP.dis_1, 300), -10000, 10000))) { on, v ->
            p.WARP.prob = if (on) v[0] else 0
            p.WARP.time = if (on) v[1] else 0
            p.WARP.dis_0 = if (on) minOf(v[2], v[3]) else 0
            p.WARP.dis_1 = if (on) maxOf(v[2], v[3]) else 0
        }

        ability(statusGroup, R.string.editor_ab_savage, p.SATK.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.SATK.prob, 30), 1, 100),
                Spec(R.string.editor_savage_pct, orDef(p.SATK.mult, 200), 1))) { on, v ->
            p.SATK.prob = if (on) v[0] else 0
            p.SATK.mult = if (on) v[1] else 0
        }

        ability(statusGroup, R.string.editor_ab_miniwave, p.MINIWAVE.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.MINIWAVE.prob, 100), 1, 100),
                Spec(R.string.editor_level, orDef(p.MINIWAVE.lv, 1), 1, 20))) { on, v ->
            p.MINIWAVE.prob = if (on) v[0] else 0
            p.MINIWAVE.lv = if (on) v[1] else 0
            p.MINIWAVE.multi = if (on) orDef(p.MINIWAVE.multi, 20) else 0
        }

        ability(statusGroup, R.string.editor_ab_minisurge, p.MINIVOLC.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.MINIVOLC.prob, 100), 1, 100),
                Spec(R.string.editor_surge_min, orDef(p.MINIVOLC.dis_0, 200), 0),
                Spec(R.string.editor_surge_max, orDef(p.MINIVOLC.dis_1, 400), 0),
                Spec(R.string.editor_level, orDef(p.MINIVOLC.time / Data.VOLC_ITV, 1), 1, 20))) { on, v ->
            p.MINIVOLC.prob = if (on) v[0] else 0
            p.MINIVOLC.dis_0 = if (on) minOf(v[1], v[2]) else 0
            p.MINIVOLC.dis_1 = if (on) maxOf(v[1], v[2]) else 0
            p.MINIVOLC.time = if (on) v[3] * Data.VOLC_ITV else 0
            p.MINIVOLC.mult = if (on) orDef(p.MINIVOLC.mult, 20) else 0
        }

        // ---- Defense & life ----
        ability(defGroup, R.string.editor_ab_strong, p.STRONG.health > 0,
            listOf(Spec(R.string.editor_strong_hp, orDef(p.STRONG.health, 50), 1, 100),
                Spec(R.string.editor_strong_mult, orDef(p.STRONG.mult, 100), 1))) { on, v ->
            p.STRONG.health = if (on) v[0] else 0
            p.STRONG.mult = if (on) v[1] else 0
        }

        ability(defGroup, R.string.editor_ab_lethal, p.LETHAL.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.LETHAL.prob, 100), 1, 100))) { on, v ->
            p.LETHAL.prob = if (on) v[0] else 0
        }

        ability(defGroup, R.string.editor_ab_dodge, p.IMUATK.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.IMUATK.prob, 30), 1, 100),
                Spec(R.string.editor_duration, orDef(p.IMUATK.time, 30), 1))) { on, v ->
            p.IMUATK.prob = if (on) v[0] else 0
            p.IMUATK.time = if (on) v[1] else 0
        }

        ability(defGroup, R.string.editor_ab_shield, p.DEMONSHIELD.hp > 0,
            listOf(Spec(R.string.editor_shield_hp, orDef(p.DEMONSHIELD.hp, 10000), 1),
                Spec(R.string.editor_shield_regen, orDef(p.DEMONSHIELD.regen, 50), 0, 100))) { on, v ->
            p.DEMONSHIELD.hp = if (on) v[0] else 0
            p.DEMONSHIELD.regen = if (on) v[1] else 0
        }

        ability(defGroup, R.string.editor_ab_burrow, p.BURROW.count != 0,
            listOf(Spec(R.string.editor_times, orDef(p.BURROW.count, 1), -1, 100),
                Spec(R.string.editor_burrow_dis, orDef(p.BURROW.dis, 300), 1))) { on, v ->
            p.BURROW.count = if (on) (if (v[0] == 0) 1 else v[0]) else 0
            p.BURROW.dis = if (on) v[1] else 0
        }

        ability(defGroup, R.string.editor_ab_revive, p.REVIVE.count != 0,
            listOf(Spec(R.string.editor_times, orDef(p.REVIVE.count, 1), -1, 100),
                Spec(R.string.editor_revive_time, orDef(p.REVIVE.time, 60), 1),
                Spec(R.string.editor_revive_hp, orDef(p.REVIVE.health, 100), 1, 100))) { on, v ->
            p.REVIVE.count = if (on) (if (v[0] == 0) 1 else v[0]) else 0
            p.REVIVE.time = if (on) v[1] else 0
            p.REVIVE.health = if (on) v[2] else 0
        }

        // ---- Immunities (tick = fully immune) ----
        ability(imuGroup, R.string.editor_imu_kb, p.IMUKB.mult > 0, emptyList()) { on, _ -> p.IMUKB.mult = if (on) 100 else 0 }
        ability(imuGroup, R.string.editor_imu_freeze, p.IMUSTOP.mult > 0, emptyList()) { on, _ -> p.IMUSTOP.mult = if (on) 100 else 0 }
        ability(imuGroup, R.string.editor_imu_slow, p.IMUSLOW.mult > 0, emptyList()) { on, _ -> p.IMUSLOW.mult = if (on) 100 else 0 }
        ability(imuGroup, R.string.editor_imu_weaken, p.IMUWEAK.mult > 0, emptyList()) { on, _ -> p.IMUWEAK.mult = if (on) 100 else 0 }
        ability(imuGroup, R.string.editor_imu_wave, p.IMUWAVE.mult > 0, emptyList()) { on, _ -> p.IMUWAVE.mult = if (on) 100 else 0 }
        ability(imuGroup, R.string.editor_imu_surge, p.IMUVOLC.mult > 0, emptyList()) { on, _ -> p.IMUVOLC.mult = if (on) 100 else 0 }
        ability(imuGroup, R.string.editor_imu_warp, p.IMUWARP.mult > 0, emptyList()) { on, _ -> p.IMUWARP.mult = if (on) 100 else 0 }
        ability(imuGroup, R.string.editor_imu_curse, p.IMUCURSE.mult > 0, emptyList()) { on, _ -> p.IMUCURSE.mult = if (on) 100 else 0 }
        ability(imuGroup, R.string.editor_imu_toxic, p.IMUPOIATK.mult > 0, emptyList()) { on, _ -> p.IMUPOIATK.mult = if (on) 100 else 0 }
    }

    /** Cat-only abilities: on/off flags plus a few with a value. */
    private fun buildCatAbilities(group: LinearLayout, p: Proc) {
        val flags = listOf(
            Data.AB_GOOD to R.string.editor_cat_good,
            Data.AB_RESIST to R.string.editor_cat_resist,
            Data.AB_RESISTS to R.string.editor_cat_resists,
            Data.AB_MASSIVE to R.string.editor_cat_massive,
            Data.AB_MASSIVES to R.string.editor_cat_massives,
            Data.AB_ONLY to R.string.editor_cat_only,
            Data.AB_ZKILL to R.string.editor_cat_zkill,
            Data.AB_WKILL to R.string.editor_cat_wkill,
            Data.AB_EKILL to R.string.editor_cat_ekill,
            Data.AB_CKILL to R.string.editor_cat_ckill,
            Data.AB_BAKILL to R.string.editor_cat_bakill,
            Data.AB_SKILL to R.string.editor_cat_skill,
            Data.AB_WAVES to R.string.editor_cat_waves,
            Data.AB_CSUR to R.string.editor_cat_csur,
            Data.AB_METALIC to R.string.editor_cat_metal,
            Data.AB_GLASS to R.string.editor_cat_glass,
            Data.AB_IMUSW to R.string.editor_cat_imusw
        )

        for ((flag, label) in flags) {
            val box = CheckBox(this)
            box.text = getString(label)
            box.setTextColor(textColor)
            box.isChecked = (ce.abi and flag) != 0
            box.setOnCheckedChangeListener { _, _ -> changed = true }
            group.addView(box)

            flagBoxes[flag] = box
        }

        fun orDef(v: Int, def: Int) = if (v != 0) v else def

        ability(group, R.string.editor_cat_metalkill, p.METALKILL.mult > 0,
            listOf(Spec(R.string.editor_cat_metalkill_pct, orDef(p.METALKILL.mult, 10), 1, 100))) { on, v ->
            p.METALKILL.mult = if (on) v[0] else 0
        }

        ability(group, R.string.editor_cat_atkbase, p.ATKBASE.mult > 0,
            listOf(Spec(R.string.editor_cat_atkbase_pct, orDef(p.ATKBASE.mult, 300), 1))) { on, v ->
            p.ATKBASE.mult = if (on) v[0] else 0
        }

        ability(group, R.string.editor_cat_bounty, p.BOUNTY.mult > 0,
            listOf(Spec(R.string.editor_cat_bounty_pct, orDef(p.BOUNTY.mult, 100), 1))) { on, v ->
            p.BOUNTY.mult = if (on) v[0] else 0
        }

        ability(group, R.string.editor_cat_break, p.BREAK.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.BREAK.prob, 100), 1, 100))) { on, v ->
            p.BREAK.prob = if (on) v[0] else 0
        }

        ability(group, R.string.editor_cat_shieldbreak, p.SHIELDBREAK.prob > 0,
            listOf(Spec(R.string.editor_chance, orDef(p.SHIELDBREAK.prob, 100), 1, 100))) { on, v ->
            p.SHIELDBREAK.prob = if (on) v[0] else 0
        }
    }

    // ---------------- Talents ----------------

    private fun talentName(type: Int): String {
        val names = resources.getStringArray(R.array.editor_talent_names)
        return if (type in names.indices) names[type] else "#$type"
    }

    /** How many values a talent type uses (0 for on/off talents). */
    private fun talentParamCount(type: Int): Int {
        if (type !in Data.PC_CORRES.indices)
            return 0

        val t = Data.PC_CORRES[type]

        // Linked immunity talents have no values of their own
        return if (t[3] != -1) 0 else t[2]
    }

    /** A readable label for value [j] of a talent [type]. */
    private fun talentParamLabel(type: Int, j: Int): String {
        val t = Data.PC_CORRES[type]

        if (t[0] == Data.PC_BASE.toInt()) {
            return getString(when (t[1]) {
                Data.PC2_COST.toInt() -> R.string.editor_tp_cost
                Data.PC2_CD.toInt() -> R.string.editor_tp_cd
                Data.PC2_SPEED.toInt() -> R.string.editor_tp_speed
                Data.PC2_HB.toInt() -> R.string.editor_tp_hb
                Data.PC2_ATK.toInt() -> R.string.editor_tp_atk
                Data.PC2_HP.toInt() -> R.string.editor_tp_hp
                Data.PC2_TBA.toInt() -> R.string.editor_tp_tba
                else -> R.string.editor_tp_value
            })
        }

        // Proc talents: name the value after the ability's own field
        val field = try {
            val fields = procs.getArr(t[1]).declaredFields
            // Behemoth dodge writes to its 2nd and 3rd fields
            val idx = if (t[1] == Data.P_BSTHUNT.toInt()) j + 1 else j
            if (idx in fields.indices) fields[idx].name else ""
        } catch (_: Exception) {
            ""
        }

        return getString(when (field) {
            "prob" -> R.string.editor_tp_prob
            "time" -> R.string.editor_tp_time
            "mult", "multi" -> R.string.editor_tp_mult
            "lv" -> R.string.editor_tp_lv
            "dis_0" -> R.string.editor_tp_dis0
            "dis_1" -> R.string.editor_tp_dis1
            "dis" -> R.string.editor_tp_dis
            "health" -> R.string.editor_tp_health
            else -> R.string.editor_tp_value
        })
    }

    private fun buildTalents(group: LinearLayout) {
        val note = TextView(this)
        note.text = getString(R.string.editor_talents_note)
        note.setTextColor(textColor)
        note.alpha = 0.7f
        group.addView(note)

        talentBox = LinearLayout(this)
        talentBox.orientation = LinearLayout.VERTICAL
        group.addView(talentBox)

        val coin = (ce as? CustomUnit)?.pcoin

        if (coin != null)
            for (d in coin.info)
                addTalentRow(TalentRow(d.copyOf(14)))

        val add = Button(this)
        add.text = getString(R.string.editor_add_talent)
        add.setOnClickListener { pickTalentType() }
        group.addView(add)
    }

    private fun pickTalentType() {
        if (talentRows.size >= 8) {
            StaticStore.showShortMessage(this, R.string.editor_max_talents)
            return
        }

        val types = Data.PC_CORRES.indices.filter { it > 0 && Data.PC_CORRES[it][0] != -1 }
        val labels = types.map<Int, CharSequence> { talentName(it) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.editor_add_talent)
            .setItems(labels) { _, which ->
                val d = IntArray(14)
                d[0] = types[which]
                d[1] = if (talentParamCount(d[0]) == 0) 1 else 10

                changed = true
                addTalentRow(TalentRow(d))
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .show()
    }

    private fun addTalentRow(row: TalentRow) {
        val type = row.data[0]
        val count = talentParamCount(type)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(8), dp(12), dp(8))
        card.setBackgroundResource(R.drawable.cell_shape)

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, dp(8))

        row.view = card

        row.title = TextView(this)
        row.title.setTypeface(null, Typeface.BOLD)
        row.title.setTextColor(textColor)
        card.addView(row.title)

        row.maxLv = field(card, R.string.editor_talent_maxlv, maxOf(1, row.data[1]).toString())

        if (count == 0) {
            row.maxLv.isEnabled = false
            row.maxLv.setText("1")
        }

        for (j in 0 until count) {
            val label = talentParamLabel(type, j)
            row.lo.add(field(card, 0, row.data[2 + j * 2].toString(), signed = true, labelText = getString(R.string.editor_talent_at_lv1).replace("_", label)))
            row.hi.add(field(card, 0, row.data[3 + j * 2].toString(), signed = true, labelText = getString(R.string.editor_talent_at_max).replace("_", label)))
        }

        row.superBox = CheckBox(this)
        row.superBox.text = getString(R.string.editor_talent_super)
        row.superBox.setTextColor(textColor)
        row.superBox.isChecked = row.data[13] == 1
        row.superBox.setOnCheckedChangeListener { _, _ -> changed = true }
        card.addView(row.superBox)

        val remove = Button(this)
        remove.text = getString(R.string.editor_remove_talent)
        remove.setOnClickListener {
            changed = true
            talentRows.remove(row)
            talentBox.removeView(card)
            renumberTalents()
        }
        card.addView(remove)

        talentRows.add(row)
        talentBox.addView(card, lp)
        renumberTalents()
    }

    private fun renumberTalents() {
        for (i in talentRows.indices) {
            val r = talentRows[i]
            r.title.text = getString(R.string.editor_talent_n).replace("_", (i + 1).toString()) + " " + talentName(r.data[0])
        }
    }

    /** Reads the talent rows; returns null (and flags the field) if something is invalid. */
    private fun readTalents(): List<IntArray>? {
        val out = ArrayList<IntArray>()

        for (row in talentRows) {
            val d = row.data.copyOf(14)

            d[1] = readInt(row.maxLv, 1, 30) ?: return null

            for (j in row.lo.indices) {
                d[2 + j * 2] = readInt(row.lo[j], -1000000) ?: return null
                d[3 + j * 2] = readInt(row.hi[j], -1000000) ?: return null
            }

            d[13] = if (row.superBox.isChecked) 1 else 0

            out.add(d)
        }

        return out
    }

    /** A tappable section title that shows/hides its contents. Returns the contents container. */
    private fun collapsible(root: LinearLayout, title: Int, expanded: Boolean): LinearLayout {
        val head = TextView(this)
        head.textSize = 16f
        head.setTypeface(null, Typeface.BOLD)
        head.setTextColor(textColor)
        head.setPadding(0, dp(14), 0, dp(6))
        root.addView(head)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        root.addView(body)

        fun refresh() {
            head.text = (if (body.visibility == View.VISIBLE) "▾  " else "▸  ") + getString(title)
        }

        refresh()

        head.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refresh()
        }

        return body
    }

    private class Spec(val label: Int, val value: Int, val min: Int, val max: Int = Int.MAX_VALUE)

    private fun ability(root: LinearLayout, title: Int, enabled: Boolean, specs: List<Spec>, apply: (Boolean, List<Int>) -> Unit) {
        val box = CheckBox(this)
        box.text = getString(title)
        box.setTextColor(textColor)
        box.setTypeface(null, Typeface.BOLD)
        box.isChecked = enabled
        root.addView(box)

        val group = LinearLayout(this)
        group.orientation = LinearLayout.VERTICAL
        group.setPadding(dp(32), 0, 0, dp(4))
        root.addView(group)

        val fields = specs.map { field(group, it.label, it.value.toString(), signed = it.min < 0) }

        group.visibility = if (enabled) View.VISIBLE else View.GONE

        box.setOnCheckedChangeListener { _, checked ->
            changed = true
            group.visibility = if (checked) View.VISIBLE else View.GONE
        }

        abilities.add(AbilityRow(box, fields, specs.map { it.min }, specs.map { it.max }, apply))
    }

    private fun save() {
        // Validate everything first so a bad field doesn't leave half the stats changed
        val hp = readInt(hpField, 1) ?: return
        val kb = readInt(kbField, 1) ?: return
        val speed = readInt(speedField, 0) ?: return
        val range = readInt(rangeField, 0) ?: return
        val width = readInt(widthField, 1) ?: return
        val tba = readInt(tbaField, 0) ?: return
        val dropF = dropField
        val priceF = priceField
        val respF = respField

        val drop = if (dropF != null) readInt(dropF, 0) ?: return else 0
        val price = if (priceF != null) readInt(priceF, 0) ?: return else 0
        val resp = if (respF != null) readInt(respF, 0) ?: return else 0

        // Attack values: damage, foreswing, area start, area end
        val atkVals = ArrayList<IntArray>()

        for (row in atkRows) {
            val dmg = readInt(row.damage, 0) ?: return
            val pre = readInt(row.pre, 0) ?: return
            val l0 = readInt(row.ld0, -100000) ?: return
            val l1 = readInt(row.ld1, -100000) ?: return
            atkVals.add(intArrayOf(dmg, pre, l0, l1))
        }

        val talents = if (isUnit) readTalents() ?: return else null

        val abilityValues = ArrayList<List<Int>>()

        for (row in abilities) {
            if (!row.box.isChecked) {
                abilityValues.add(emptyList())
                continue
            }

            val vals = ArrayList<Int>()

            for (j in row.fields.indices) {
                vals.add(readInt(row.fields[j], row.mins[j], row.maxs[j]) ?: return)
            }

            abilityValues.add(vals)
        }

        ce.hp = hp
        ce.hb = kb
        ce.speed = speed
        ce.range = range
        ce.width = width
        ce.tba = tba
        (ce as? CustomEnemy)?.drop = drop

        (ce as? CustomUnit)?.let {
            it.price = price
            it.resp = resp
        }

        // Rebuild the attack list (keeps each existing attack's other settings)
        val newAtks = ArrayList<AtkDataModel>()

        for (i in atkRows.indices) {
            val row = atkRows[i]
            val v = atkVals[i]
            val adm = row.model ?: AtkDataModel(ce)

            adm.atk = v[0]
            adm.pre = v[1]
            adm.range = row.area.isChecked

            // Both 0 = normal attack; otherwise start/end of the hit area
            if (v[2] == 0 && v[3] == 0) {
                adm.ld0 = 0
                adm.ld1 = 0
            } else {
                adm.ld0 = minOf(v[2], v[3])
                adm.ld1 = maxOf(v[2], v[3])
            }

            newAtks.add(adm)
        }

        ce.atks = newAtks.toTypedArray()

        // Traits: replace only the ones this screen manages, keep any others
        for ((trait, box) in traitBoxes) {
            ce.traits.remove(trait)

            if (box.isChecked)
                ce.traits.add(trait)
        }

        // Abilities: write back and share them across all attacks
        for (i in abilities.indices) {
            abilities[i].apply(abilities[i].box.isChecked, abilityValues[i])
        }

        // Cat flags: clear the ones this screen manages, then set the ticked ones
        if (flagBoxes.isNotEmpty()) {
            var abi = ce.abi

            for ((flag, box) in flagBoxes) {
                abi = abi and flag.inv()

                if (box.isChecked)
                    abi = abi or flag
            }

            ce.abi = abi
        }

        ce.common = true
        ce.rep.proc = procs

        // Talents (units only)
        val cuT = ce as? CustomUnit

        if (cuT != null && talents != null) {
            if (talents.isEmpty()) {
                cuT.pcoin = null
            } else {
                val coin = cuT.pcoin ?: PCoin(cuT)
                coin.info.clear()
                coin.info.addAll(talents)
                coin.max = IntArray(talents.size) { talents[it][1] }
                cuT.pcoin = coin
            }
        }

        ce.updateAllProc()

        // Recalculate the fully-talented version after stats/abilities changed
        try {
            (ce as? CustomUnit)?.pcoin?.update()
        } catch (e: Exception) {
            Log.e("EnemyEditor", "Failed to update talents", e)
        }

        val name = nameField.text.toString().trim()
        if (name.isNotEmpty())
            names.put(name)

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
