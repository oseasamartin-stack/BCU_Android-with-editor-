package com.mandarin.bcu.androidutil.pack

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.core.content.FileProvider
import com.mandarin.bcu.EnemyEditor
import com.mandarin.bcu.PackManagement
import com.mandarin.bcu.StageEditor
import com.mandarin.bcu.R
import com.mandarin.bcu.androidutil.StaticStore
import common.battle.data.CustomEnemy
import common.battle.data.CustomUnit
import common.battle.data.PCoin
import common.CommonStatic
import common.io.PackLoader
import common.pack.Identifier
import common.pack.PackData
import common.pack.Source
import common.pack.UserProfile
import common.util.Data
import common.util.anim.AnimCE
import common.util.lang.MultiLangCont
import common.util.pack.Background
import common.util.stage.CastleImg
import common.util.stage.CastleList
import common.util.stage.Music
import common.util.stage.Stage
import common.util.stage.StageMap
import common.util.unit.AbEnemy
import common.util.unit.Enemy
import common.util.unit.Form
import common.util.unit.Unit as BCUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [Editor] Pack editing actions added in this fork.
 */
object EditorActions {

    /** Pick a built-in enemy and add an editable copy of it to a workspace pack. */
    fun showAddEnemyDialog(ac: Activity, pack: PackData.UserPack) {
        pickEnemy(ac, null, R.string.editor_add_enemy) { src -> addEnemyCopy(ac, pack, src) }
    }

    /**
     * Searchable enemy picker. Lists [pack]'s own enemies first (if given), then all built-in enemies.
     */
    fun pickEnemy(ac: Activity, pack: PackData.UserPack?, title: Int = R.string.editor_pick_enemy, onPick: (Enemy) -> Unit) {
        val all = ArrayList<Enemy>()

        if (pack != null) {
            all.addAll(pack.enemies.list.filterNotNull())

            for (dep in parentPacks(pack))
                all.addAll(dep.enemies.list.filterNotNull())
        }

        all.addAll(UserProfile.getBCData().enemies.list.filterNotNull())

        val labels = all.map { enemyLabel(it.id) }

        val shownEnemies = ArrayList(all)
        val shownLabels = ArrayList(labels)

        val pad = (16 * ac.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(ac)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(pad, pad / 2, pad, 0)

        val search = EditText(ac)
        search.hint = ac.getString(R.string.editor_search_enemy)
        search.setSingleLine()

        val list = ListView(ac)
        val listAdapter = ArrayAdapter(ac, android.R.layout.simple_list_item_1, shownLabels)
        list.adapter = listAdapter

        layout.addView(search)
        layout.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (400 * ac.resources.displayMetrics.density).toInt()))

        val dialog = AlertDialog.Builder(ac)
            .setTitle(title)
            .setView(layout)
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""

                shownEnemies.clear()
                shownLabels.clear()

                for (i in all.indices) {
                    if (q.isEmpty() || labels[i].lowercase().contains(q)) {
                        shownEnemies.add(all[i])
                        shownLabels.add(labels[i])
                    }
                }

                listAdapter.notifyDataSetChanged()
            }
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val picked = shownEnemies[position]

            dialog.dismiss()

            onPick(picked)
        }

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    /** List the pack's enemies and open the stat editor for the one tapped. */
    fun showEditEnemiesDialog(ac: Activity, pack: PackData.UserPack) {
        val enemies = pack.enemies.list.filterNotNull()

        if (enemies.isEmpty()) {
            StaticStore.showShortMessage(ac, R.string.editor_no_enemies)
            return
        }

        val labels = enemies.map<Enemy, CharSequence> { "${Data.trio(it.id.id)} - ${it.names}" }.toTypedArray()

        val dialog = AlertDialog.Builder(ac)
            .setTitle(R.string.editor_edit_enemies)
            .setItems(labels) { _, which ->
                val intent = Intent(ac, EnemyEditor::class.java)
                intent.putExtra(EnemyEditor.EXTRA_PACK, pack.sid)
                intent.putExtra(EnemyEditor.EXTRA_INDEX, enemies[which].id.id)
                ac.startActivity(intent)
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    /** Export a workspace pack to a .pack.bcuzip file, then open the share sheet. */
    fun exportPack(ac: Activity, pack: PackData.UserPack) {
        val pad = (16 * ac.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(ac)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(pad, pad / 2, pad, 0)

        val protect = CheckBox(ac)
        protect.text = ac.getString(R.string.editor_export_protect)
        layout.addView(protect)

        val pw = EditText(ac)
        pw.hint = ac.getString(R.string.editor_export_pw_hint)
        pw.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        pw.visibility = View.GONE
        layout.addView(pw)

        protect.setOnCheckedChangeListener { _, checked -> pw.visibility = if (checked) View.VISIBLE else View.GONE }

        val dialog = AlertDialog.Builder(ac)
            .setTitle(R.string.editor_export)
            .setView(layout)
            .setPositiveButton(R.string.editor_export_go, null)
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parentPw = if (protect.isChecked) pw.text.toString() else null

                if (parentPw != null && parentPw.isEmpty()) {
                    pw.error = ac.getString(R.string.editor_export_pw_empty)
                    return@setOnClickListener
                }

                dialog.dismiss()
                doExport(ac, pack, parentPw)
            }
        }

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    private fun doExport(ac: Activity, pack: PackData.UserPack, parentPassword: String?) {
        val source = pack.source as? Source.Workspace ?: return

        val progress = AlertDialog.Builder(ac)
            .setMessage(R.string.editor_exporting)
            .setCancelable(false)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            progress.show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val file = try {
                // Same export the PC version uses; writes <app files>/exports/<id>.pack.bcuzip
                source.export(pack, "", parentPassword) { _ -> }

                val f = CommonStatic.ctx.getAuxFile("./exports/" + pack.sid + ".pack.bcuzip")

                if (f.exists()) f else null
            } catch (e: Exception) {
                Log.e("EditorActions", "Failed to export pack", e)

                null
            }

            ac.runOnUiThread {
                if (progress.isShowing)
                    progress.dismiss()

                if (file == null) {
                    StaticStore.showShortMessage(ac, R.string.editor_export_fail)
                    return@runOnUiThread
                }

                try {
                    val uri = FileProvider.getUriForFile(ac, ac.packageName + ".provider", file)

                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "*/*"
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    ac.startActivity(Intent.createChooser(intent, ac.getString(R.string.editor_export_share)))
                } catch (e: Exception) {
                    Log.e("EditorActions", "Failed to share pack", e)

                    StaticStore.showShortMessage(ac, ac.getString(R.string.editor_export_saved).replace("_", file.absolutePath))
                }
            }
        }
    }

    private fun addEnemyCopy(ac: Activity, pack: PackData.UserPack, src: Enemy) {
        val progress = AlertDialog.Builder(ac)
            .setMessage(R.string.editor_copying)
            .setCancelable(false)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            progress.show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                copyEnemy(pack, src)
            } catch (e: Exception) {
                Log.e("EditorActions", "Failed to copy enemy", e)

                null
            }

            ac.runOnUiThread {
                if (progress.isShowing)
                    progress.dismiss()

                if (result == null) {
                    StaticStore.showShortMessage(ac, R.string.editor_add_enemy_fail)
                } else {
                    PackManagement.needReload = true

                    StaticStore.showShortMessage(ac, ac.getString(R.string.editor_add_enemy_done).replace("_", result.names.toString()))
                }
            }
        }
    }

    /** Same idea as the PC version: copy the animation into the pack, then copy the stats. */
    private fun copyEnemy(pack: PackData.UserPack, src: Enemy): Enemy {
        // Make sure the built-in animation is loaded before copying it
        src.anim.check()

        // workspace/<pack id>/animations/enemy_XXX/  (renamed _0, _1... if it already exists)
        val rl = Source.ResourceLocation(pack.sid, "enemy_" + Data.trio(src.id.id), Source.BasePath.ANIM)
        Source.Workspace.validate(rl)

        // Copies sprite, imgcut, model and animations, and saves them
        val anim = AnimCE(rl, src.anim)

        val ce = CustomEnemy()
        ce.importData(src.de)

        val id = Identifier<AbEnemy>(pack.sid, Enemy::class.java, pack.enemies.nextInd())
        val enemy = Enemy(id, anim, ce)

        val name = enemyName(src)
        enemy.names.put(if (name.isBlank()) "Enemy ${Data.trio(src.id.id)}" else "$name (copy)")

        pack.enemies.add(enemy)

        Source.Workspace.saveWorkspace()

        return enemy
    }

    /** "123 - Name" for built-in enemies, "pack - 000 - Name" for pack enemies. */
    fun enemyLabel(id: Identifier<AbEnemy>?): String {
        if (id == null)
            return "?"

        val e = try { id.get() } catch (_: Exception) { null }

        val name = if (e is Enemy) enemyName(e) else ""
        val num = Data.trio(id.id)

        return if (id.pack == Identifier.DEF) {
            "$num - $name"
        } else {
            "${StaticStore.getPackName(id.pack)} - $num - $name"
        }
    }

    // ---------- Stages ----------

    /** Ask for a line of text (used for map / stage names). */
    private fun askText(ac: Activity, title: Int, initial: String, onOk: (String) -> Unit) {
        val pad = (16 * ac.resources.displayMetrics.density).toInt()

        val input = EditText(ac)
        input.setSingleLine()
        input.setText(initial)

        val layout = LinearLayout(ac)
        layout.setPadding(pad, pad / 2, pad, 0)
        layout.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(ac)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    private fun save(ac: Activity): Boolean {
        return try {
            Source.Workspace.saveWorkspace()
            PackManagement.needReload = true
            true
        } catch (e: Exception) {
            Log.e("EditorActions", "Failed to save", e)
            StaticStore.showShortMessage(ac, R.string.editor_save_fail)
            false
        }
    }

    /** List the pack's maps, with an option to make a new one. */
    fun showStageMaps(ac: Activity, pack: PackData.UserPack) {
        val maps = pack.mc.maps.list.filterNotNull()

        val labels = ArrayList<CharSequence>()
        labels.add(ac.getString(R.string.editor_new_map))
        maps.forEach { labels.add("${Data.trio(it.id.id)} - ${it.names}") }

        val dialog = AlertDialog.Builder(ac)
            .setTitle(R.string.editor_edit_stages)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    askText(ac, R.string.editor_map_name, "") { name ->
                        val sm = StageMap(pack.mc.getNextID())

                        if (name.isNotEmpty())
                            sm.names.put(name)

                        pack.mc.maps.add(sm)

                        if (save(ac))
                            showMapStages(ac, pack, sm)
                    }
                } else {
                    showMapStages(ac, pack, maps[which - 1])
                }
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    /** List a map's stages, with options to add a stage or rename the map. */
    private fun showMapStages(ac: Activity, pack: PackData.UserPack, sm: StageMap) {
        val stages = sm.list.list.filterNotNull()

        val labels = ArrayList<CharSequence>()
        labels.add(ac.getString(R.string.editor_new_stage))
        labels.add(ac.getString(R.string.editor_rename_map))
        labels.add(ac.getString(R.string.editor_delete_map))
        stages.forEach { labels.add("${Data.trio(it.id.id)} - ${it.names}") }

        val dialog = AlertDialog.Builder(ac)
            .setTitle(sm.names.toString())
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val st = Stage(sm)
                        sm.add(st)

                        if (save(ac))
                            openStage(ac, pack, sm, st)
                    }
                    1 -> {
                        askText(ac, R.string.editor_map_name, sm.names.toString()) { name ->
                            if (name.isNotEmpty()) {
                                sm.names.put(name)
                                save(ac)
                            }
                        }
                    }
                    2 -> {
                        confirm(ac, R.string.editor_delete_map_title, R.string.editor_delete_map_msg) {
                            pack.mc.maps.remove(sm)
                            save(ac)
                        }
                    }
                    else -> openStage(ac, pack, sm, stages[which - 3])
                }
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    /** Simple yes/no confirmation. */
    fun confirm(ac: Activity, title: Int, msg: Int, onYes: () -> Unit) {
        val dialog = AlertDialog.Builder(ac)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(R.string.editor_delete) { _, _ -> onYes() }
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    // ---------- Resource pickers (stage looks & sound) ----------

    /** Searchable list with optional thumbnails. Index 0 is always "Default". */
    private fun pickFromList(ac: Activity, title: Int, labels: List<String>, image: ((Int) -> Bitmap?)?, onPick: (Int) -> Unit) {
        val density = ac.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val shown = ArrayList(labels.indices.toList())

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(ac).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                    addView(ImageView(ac), LinearLayout.LayoutParams((64 * density).toInt(), (48 * density).toInt()))
                    addView(TextView(ac).apply { setPadding(pad / 2, 0, 0, 0); textSize = 16f })
                }

                val idx = shown[position]
                val iv = row.getChildAt(0) as ImageView
                val tv = row.getChildAt(1) as TextView

                tv.text = labels[idx]

                val bmp = if (image == null || idx == 0) null else try { image(idx) } catch (_: Exception) { null }
                iv.setImageBitmap(bmp)
                iv.visibility = if (image == null) View.GONE else View.VISIBLE

                return row
            }
        }

        val search = EditText(ac)
        search.hint = ac.getString(R.string.editor_search)
        search.setSingleLine()

        val list = ListView(ac)
        list.adapter = adapter

        val layout = LinearLayout(ac)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(pad, pad / 2, pad, 0)
        layout.addView(search)
        layout.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (400 * density).toInt()))

        val dialog = AlertDialog.Builder(ac)
            .setTitle(title)
            .setView(layout)
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                shown.clear()
                labels.indices.filterTo(shown) { q.isEmpty() || labels[it].lowercase().contains(q) }
                adapter.notifyDataSetChanged()
            }
        })

        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onPick(shown[position])
        }

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    private fun packPrefix(pack: String): String {
        return if (pack == Identifier.DEF) "BC" else StaticStore.getPackName(pack)
    }

    fun bgLabel(ac: Activity, id: Identifier<Background>?): String {
        return if (id == null) ac.getString(R.string.editor_default) else "${packPrefix(id.pack)} - ${Data.trio(id.id)}"
    }

    fun musicLabel(ac: Activity, id: Identifier<Music>?): String {
        return if (id == null) ac.getString(R.string.editor_none) else "${packPrefix(id.pack)} - ${Data.trio(id.id)}"
    }

    fun castleLabel(ac: Activity, id: Identifier<CastleImg>?): String {
        if (id == null)
            return ac.getString(R.string.editor_default)

        val list = CastleList.map()[id.pack]
        val listName = when {
            list is CastleList.DefCasList -> "BC ${list.str}"
            id.pack.length == 8 -> StaticStore.getPackName(id.pack)
            else -> id.pack
        }

        return "$listName - ${Data.trio(id.id)}"
    }

    fun pickBackground(ac: Activity, pack: PackData.UserPack, onPick: (Identifier<Background>?) -> Unit) {
        val items = ArrayList<Background?>()
        items.add(null)
        items.addAll(UserProfile.getBCData().bgs.list.filterNotNull())
        items.addAll(pack.bgs.list.filterNotNull())
        parentPacks(pack).forEach { items.addAll(it.bgs.list.filterNotNull()) }

        val labels = items.map { bgLabel(ac, it?.id) }

        pickFromList(ac, R.string.editor_stage_bg, labels, null) { i -> onPick(items[i]?.id) }
    }

    fun pickMusic(ac: Activity, pack: PackData.UserPack, onPick: (Identifier<Music>?) -> Unit) {
        val items = ArrayList<Music?>()
        items.add(null)
        items.addAll(UserProfile.getBCData().musics.list.filterNotNull())
        items.addAll(pack.musics.list.filterNotNull())
        parentPacks(pack).forEach { items.addAll(it.musics.list.filterNotNull()) }

        val labels = items.map { musicLabel(ac, it?.id) }

        pickFromList(ac, R.string.editor_stage_music, labels, null) { i -> onPick(items[i]?.id) }
    }

    fun pickCastle(ac: Activity, stage: Stage, onPick: (Identifier<CastleImg>?) -> Unit) {
        val items = ArrayList<CastleImg?>()
        items.add(null)

        for (list in CastleList.from(stage))
            items.addAll(list.list.filterNotNull())

        val labels = items.map { castleLabel(ac, it?.id) }

        pickFromList(ac, R.string.editor_stage_castle, labels, { i -> items[i]?.img?.img?.bimg() as? Bitmap }) { i ->
            onPick(items[i]?.id)
        }
    }

    // ---------- Units ----------

    private fun formName(f: Form): String {
        return try { MultiLangCont.get(f) ?: f.names.toString() } catch (_: Exception) { "" }
    }

    private fun unitLabel(u: BCUnit): String {
        val first = u.forms.firstOrNull()
        val name = if (first == null) "" else formName(first)
        val num = Data.trio(u.id.id)

        return if (u.id.pack == Identifier.DEF) "$num - $name" else "${StaticStore.getPackName(u.id.pack)} - $num - $name"
    }

    /** List the pack's units, with an option to add a copy of a built-in cat. */
    fun showUnits(ac: Activity, pack: PackData.UserPack) {
        val units = pack.units.list.filterNotNull()

        val labels = ArrayList<CharSequence>()
        labels.add(ac.getString(R.string.editor_add_unit))
        units.forEach { labels.add(unitLabel(it)) }

        val dialog = AlertDialog.Builder(ac)
            .setTitle(R.string.editor_edit_units)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0)
                    pickBCUnit(ac) { src -> addUnitCopy(ac, pack, src) }
                else
                    showUnitOptions(ac, pack, units[which - 1])
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    private fun pickBCUnit(ac: Activity, onPick: (BCUnit) -> Unit) {
        val all = UserProfile.getBCData().units.list.filterNotNull().filter { it.forms.isNotEmpty() }
        val labels = listOf(ac.getString(R.string.main_file_cancel)) + all.map { unitLabel(it) }

        pickFromList(ac, R.string.editor_add_unit, labels, { i -> all[i - 1].forms[0].anim.edi?.img?.bimg() as? Bitmap }) { i ->
            if (i > 0)
                onPick(all[i - 1])
        }
    }

    private fun showUnitOptions(ac: Activity, pack: PackData.UserPack, u: BCUnit) {
        val labels = ArrayList<CharSequence>()

        for (f in u.forms)
            labels.add(ac.getString(R.string.editor_edit_form).replace("_", "${f.fid + 1}: ${formName(f)}"))

        // Extra options after the forms, in this order
        val addFormIdx = u.forms.size
        val removeFormIdx = u.forms.size + 1
        val settingsIdx = u.forms.size + 2

        labels.add(ac.getString(R.string.editor_add_form).replace("_", u.forms.size.toString()))
        labels.add(ac.getString(R.string.editor_remove_form))
        labels.add(ac.getString(R.string.editor_unit_settings))
        labels.add(ac.getString(R.string.editor_delete_unit))

        val dialog = AlertDialog.Builder(ac)
            .setTitle(unitLabel(u))
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < u.forms.size -> {
                        val intent = Intent(ac, EnemyEditor::class.java)
                        intent.putExtra(EnemyEditor.EXTRA_PACK, pack.sid)
                        intent.putExtra(EnemyEditor.EXTRA_UNIT, u.id.id)
                        intent.putExtra(EnemyEditor.EXTRA_FORM, which)
                        ac.startActivity(intent)
                    }
                    which == addFormIdx -> {
                        if (u.forms.size >= 4)
                            StaticStore.showShortMessage(ac, R.string.editor_max_forms)
                        else
                            addForm(ac, pack, u)
                    }
                    which == removeFormIdx -> {
                        if (u.forms.size <= 1)
                            StaticStore.showShortMessage(ac, R.string.editor_need_form)
                        else
                            confirm(ac, R.string.editor_remove_form_title, R.string.editor_remove_form_msg) {
                                removeLastForm(ac, pack, u)
                            }
                    }
                    which == settingsIdx -> showUnitSettings(ac, u)
                    else -> confirm(ac, R.string.editor_delete_unit_title, R.string.editor_delete_unit_msg) {
                        deleteUnit(ac, pack, u)
                    }
                }
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    /** Rarity and level caps, which belong to the unit rather than a form. */
    private fun showUnitSettings(ac: Activity, u: BCUnit) {
        val pad = (16 * ac.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(ac)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(pad, pad / 2, pad, 0)

        fun label(res: Int) {
            val t = TextView(ac)
            t.text = ac.getString(res)
            t.setPadding(0, pad / 2, 0, 0)
            layout.addView(t)
        }

        label(R.string.editor_rarity)

        val rarities = listOf(R.string.editor_rar_normal_short, R.string.editor_rar_special_short, R.string.editor_rar_rare_short,
            R.string.editor_rar_super_short, R.string.editor_rar_uber_short, R.string.editor_rar_legend_short).map { ac.getString(it) }

        val spinner = Spinner(ac)
        spinner.adapter = ArrayAdapter(ac, android.R.layout.simple_spinner_dropdown_item, rarities)
        spinner.setSelection(u.rarity.coerceIn(0, rarities.size - 1))
        layout.addView(spinner)

        label(R.string.editor_max_lv)
        val maxLv = EditText(ac)
        maxLv.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        maxLv.setText(u.max.toString())
        layout.addView(maxLv)

        label(R.string.editor_max_plus)
        val maxPlus = EditText(ac)
        maxPlus.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        maxPlus.setText(u.maxp.toString())
        layout.addView(maxPlus)

        val dialog = AlertDialog.Builder(ac)
            .setTitle(R.string.editor_unit_settings)
            .setView(layout)
            .setPositiveButton(R.string.editor_save, null)
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        dialog.setOnShowListener {
            // Validate before closing
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mx = maxLv.text.toString().trim().toIntOrNull()
                val mp = maxPlus.text.toString().trim().toIntOrNull()

                if (mx == null || mx < 1) {
                    maxLv.error = ac.getString(R.string.editor_invalid_number).replace("_", "1")
                    return@setOnClickListener
                }

                if (mp == null || mp < 0) {
                    maxPlus.error = ac.getString(R.string.editor_invalid_number).replace("_", "0")
                    return@setOnClickListener
                }

                u.rarity = spinner.selectedItemPosition
                u.max = mx
                u.maxp = mp

                if (save(ac))
                    StaticStore.showShortMessage(ac, R.string.editor_saved)

                dialog.dismiss()
            }
        }

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    private fun addUnitCopy(ac: Activity, pack: PackData.UserPack, src: BCUnit) {
        val progress = AlertDialog.Builder(ac)
            .setMessage(R.string.editor_copying_unit)
            .setCancelable(false)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            progress.show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                copyUnit(pack, src)
            } catch (e: Exception) {
                Log.e("EditorActions", "Failed to copy unit", e)
                null
            }

            ac.runOnUiThread {
                if (progress.isShowing)
                    progress.dismiss()

                if (result == null) {
                    StaticStore.showShortMessage(ac, R.string.editor_add_unit_fail)
                } else {
                    PackManagement.needReload = true
                    StaticStore.showShortMessage(ac, ac.getString(R.string.editor_add_enemy_done).replace("_", unitLabel(result)))
                }
            }
        }
    }

    /** Copy every form of a built-in cat (animation + stats + talents) into the pack. */
    private fun copyUnit(pack: PackData.UserPack, src: BCUnit): BCUnit {
        val id = Identifier<BCUnit>(pack.sid, BCUnit::class.java, pack.units.nextInd())
        val u = BCUnit(id)

        u.rarity = src.rarity
        u.max = src.max
        u.maxp = src.maxp
        u.lv = src.lv
        u.lv?.units?.add(u)

        val forms = ArrayList<Form>()

        for (i in src.forms.indices) {
            val sf = src.forms[i]

            sf.anim.check()

            val rl = Source.ResourceLocation(pack.sid, "unit_" + Data.trio(src.id.id) + "_" + i, Source.BasePath.ANIM)
            Source.Workspace.validate(rl)

            val anim = AnimCE(rl, sf.anim)

            val cu = CustomUnit()
            cu.importData(sf.du)

            val name = formName(sf)

            forms.add(Form(u, i, if (name.isBlank()) "Form ${i + 1}" else name, anim, cu))
        }

        u.forms = forms.toTypedArray()

        pack.units.add(u)

        Source.Workspace.saveWorkspace()

        return u
    }

    /** Add a new form copied from the current last form (animation, stats and talents). */
    private fun addForm(ac: Activity, pack: PackData.UserPack, u: BCUnit) {
        val progress = AlertDialog.Builder(ac)
            .setMessage(R.string.editor_copying_form)
            .setCancelable(false)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            progress.show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val ok = try {
                val last = u.forms.last()
                val i = u.forms.size

                last.anim.check()

                val rl = Source.ResourceLocation(pack.sid, "unit_" + Data.trio(u.id.id) + "_" + i, Source.BasePath.ANIM)
                Source.Workspace.validate(rl)

                val anim = AnimCE(rl, last.anim)

                val cu = CustomUnit()
                cu.importData(last.du)

                // Copy talents exactly. importData() re-converts talent data that is
                // already converted, so rebuild the talent list from the source as-is.
                val srcCoin = (last.du as? CustomUnit)?.pcoin

                if (srcCoin != null) {
                    val pc = PCoin(cu)
                    pc.max = srcCoin.max?.clone()
                    srcCoin.info.forEach { pc.info.add(it.clone()) }
                    pc.trait.addAll(srcCoin.trait)
                    pc.update()
                } else {
                    cu.pcoin = null
                }

                val name = formName(last)
                val form = Form(u, i, if (name.isBlank()) "Form ${i + 1}" else "$name+", anim, cu)

                u.forms = u.forms + form

                Source.Workspace.saveWorkspace()
                true
            } catch (e: Exception) {
                Log.e("EditorActions", "Failed to add form", e)
                false
            }

            ac.runOnUiThread {
                if (progress.isShowing)
                    progress.dismiss()

                if (ok) {
                    PackManagement.needReload = true
                    StaticStore.showShortMessage(ac, R.string.editor_form_added)
                } else {
                    StaticStore.showShortMessage(ac, R.string.editor_save_fail)
                }
            }
        }
    }

    private fun removeLastForm(ac: Activity, pack: PackData.UserPack, u: BCUnit) {
        val last = u.forms.last()

        u.forms = u.forms.sliceArray(0 until u.forms.size - 1)

        // Delete its animation folder unless another form still uses it
        val anim = last.anim as? AnimCE

        if (anim != null && anim.id.pack == pack.sid &&
            u.forms.none { (it.anim as? AnimCE)?.id?.toString() == anim.id.toString() }) {
            try {
                CommonStatic.ctx.getWorkspaceFile(anim.id.getPath()).deleteRecursively()
            } catch (e: Exception) {
                Log.e("EditorActions", "Failed to delete form animation", e)
            }
        }

        if (save(ac))
            StaticStore.showShortMessage(ac, R.string.editor_deleted)
    }

    private fun deleteUnit(ac: Activity, pack: PackData.UserPack, u: BCUnit) {
        pack.units.remove(u)
        u.lv?.units?.remove(u)

        // Remove its animation folders
        for (f in u.forms) {
            val anim = f.anim as? AnimCE ?: continue

            if (anim.id.pack == pack.sid) {
                try {
                    CommonStatic.ctx.getWorkspaceFile(anim.id.getPath()).deleteRecursively()
                } catch (e: Exception) {
                    Log.e("EditorActions", "Failed to delete unit animation", e)
                }
            }
        }

        if (save(ac))
            StaticStore.showShortMessage(ac, R.string.editor_deleted)
    }

    // ---------- Pack settings ----------

    /** Loaded packs this pack depends on (its "parent" packs). */
    private fun parentPacks(pack: PackData.UserPack): List<PackData.UserPack> {
        val deps = pack.desc.dependency ?: return emptyList()
        return deps.mapNotNull { UserProfile.getUserPack(it) }
    }

    fun showPackSettings(ac: Activity, pack: PackData.UserPack) {
        val density = ac.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val scroll = android.widget.ScrollView(ac)

        val layout = LinearLayout(ac)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(pad, pad / 2, pad, 0)
        scroll.addView(layout)

        fun label(res: Int) {
            val t = TextView(ac)
            t.text = ac.getString(res)
            t.setPadding(0, pad / 2, 0, 0)
            layout.addView(t)
        }

        val idText = TextView(ac)
        idText.text = ac.getString(R.string.editor_pack_id).replace("_", pack.sid)
        idText.alpha = 0.7f
        layout.addView(idText)

        label(R.string.editor_pack_name)
        val name = EditText(ac)
        name.setSingleLine()
        name.setText(pack.desc.names.toString())
        layout.addView(name)

        label(R.string.editor_pack_author)
        val author = EditText(ac)
        author.setSingleLine()
        author.setText(pack.desc.author ?: "")
        layout.addView(author)

        label(R.string.editor_pack_desc)
        val desc = EditText(ac)
        desc.minLines = 3
        desc.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        desc.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        desc.setText(pack.desc.desc ?: "")
        layout.addView(desc)

        label(R.string.editor_pack_version)
        val version = EditText(ac)
        version.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        version.setText(pack.desc.version.toString())
        layout.addView(version)

        val allowAnim = CheckBox(ac)
        allowAnim.text = ac.getString(R.string.editor_pack_allowanim)
        allowAnim.isChecked = pack.desc.allowAnim
        layout.addView(allowAnim)

        // Parent packs, edited in their own dialog; kept here until Save
        val deps = ArrayList(pack.desc.dependency ?: ArrayList())

        label(R.string.editor_pack_parents)

        val parentsButton = android.widget.Button(ac)
        parentsButton.isAllCaps = false

        fun refreshParents() {
            parentsButton.text = if (deps.isEmpty())
                ac.getString(R.string.editor_none)
            else
                deps.joinToString(", ") { StaticStore.getPackName(it) }
        }

        refreshParents()

        parentsButton.setOnClickListener {
            pickParents(ac, pack, deps) { refreshParents() }
        }

        layout.addView(parentsButton)

        val note = TextView(ac)
        note.text = ac.getString(R.string.editor_pack_parents_note)
        note.alpha = 0.7f
        layout.addView(note)

        val dialog = AlertDialog.Builder(ac)
            .setTitle(R.string.editor_pack_settings)
            .setView(scroll)
            .setPositiveButton(R.string.editor_save, null)
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = version.text.toString().trim().toIntOrNull()

                if (v == null || v < 0) {
                    version.error = ac.getString(R.string.editor_invalid_number).replace("_", "0")
                    return@setOnClickListener
                }

                val n = name.text.toString().trim()
                if (n.isNotEmpty())
                    pack.desc.names.put(n)

                pack.desc.author = author.text.toString().trim()
                pack.desc.desc = desc.text.toString()
                pack.desc.version = v
                pack.desc.allowAnim = allowAnim.isChecked

                if (pack.desc.dependency == null)
                    pack.desc.dependency = ArrayList()

                pack.desc.dependency.clear()
                pack.desc.dependency.addAll(deps)

                if (save(ac))
                    StaticStore.showShortMessage(ac, R.string.editor_saved)

                dialog.dismiss()
            }
        }

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
    }

    /**
     * Choose parent packs. A pack protected with a parent password can only be
     * added if the correct password is entered (the author's choice is respected).
     */
    private fun pickParents(ac: Activity, pack: PackData.UserPack, deps: ArrayList<String>, onDone: () -> Unit) {
        val candidates = UserProfile.getUserPacks().filter {
            // Not itself, and not a pack that already depends on this one
            it.sid != pack.sid && !(it.desc.dependency?.contains(pack.sid) ?: false)
        }

        if (candidates.isEmpty()) {
            StaticStore.showShortMessage(ac, R.string.editor_no_parents)
            return
        }

        val labels = candidates.map<PackData.UserPack, CharSequence> {
            val lock = if (it.desc.parentPassword != null) " 🔒" else ""
            StaticStore.getPackName(it.sid) + lock
        }.toTypedArray()

        val checked = BooleanArray(candidates.size) { deps.contains(candidates[it].sid) }

        AlertDialog.Builder(ac)
            .setTitle(R.string.editor_pack_parents)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val toUnlock = ArrayList<PackData.UserPack>()

                for (i in candidates.indices) {
                    val sid = candidates[i].sid

                    if (!checked[i]) {
                        deps.remove(sid)
                    } else if (!deps.contains(sid)) {
                        if (candidates[i].desc.parentPassword != null)
                            toUnlock.add(candidates[i])
                        else
                            deps.add(sid)
                    }
                }

                unlockParents(ac, toUnlock, 0, deps, onDone)
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .show()
    }

    /** Ask for each protected parent's password in turn. */
    private fun unlockParents(ac: Activity, list: List<PackData.UserPack>, i: Int, deps: ArrayList<String>, onDone: () -> Unit) {
        if (i >= list.size) {
            onDone()
            return
        }

        val p = list[i]
        val pad = (16 * ac.resources.displayMetrics.density).toInt()

        val pw = EditText(ac)
        pw.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        val layout = LinearLayout(ac)
        layout.setPadding(pad, pad / 2, pad, 0)
        layout.addView(pw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(ac)
            .setTitle(ac.getString(R.string.editor_parent_pw).replace("_", StaticStore.getPackName(p.sid)))
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val hash = PackLoader.getMD5(pw.text.toString().toByteArray(Charsets.UTF_8), 16)

                if (hash.contentEquals(p.desc.parentPassword))
                    deps.add(p.sid)
                else
                    StaticStore.showShortMessage(ac, R.string.editor_parent_pw_wrong)

                unlockParents(ac, list, i + 1, deps, onDone)
            }
            .setNegativeButton(R.string.main_file_cancel) { _, _ -> unlockParents(ac, list, i + 1, deps, onDone) }
            .show()
    }

    private fun openStage(ac: Activity, pack: PackData.UserPack, sm: StageMap, st: Stage) {
        val intent = Intent(ac, StageEditor::class.java)
        intent.putExtra(StageEditor.EXTRA_PACK, pack.sid)
        intent.putExtra(StageEditor.EXTRA_MAP, sm.id.id)
        intent.putExtra(StageEditor.EXTRA_STAGE, st.id.id)
        ac.startActivity(intent)
    }

    private fun enemyName(e: Enemy): String {
        return try {
            MultiLangCont.get(e) ?: e.names.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
