package com.mandarin.bcu.androidutil.pack

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import common.CommonStatic
import common.pack.Identifier
import common.pack.PackData
import common.pack.Source
import common.pack.UserProfile
import common.util.Data
import common.util.anim.AnimCE
import common.util.lang.MultiLangCont
import common.util.stage.Stage
import common.util.stage.StageMap
import common.util.unit.AbEnemy
import common.util.unit.Enemy
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

        if (pack != null)
            all.addAll(pack.enemies.list.filterNotNull())

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
                source.export(pack, "", null) { _ -> }

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
                    else -> openStage(ac, pack, sm, stages[which - 2])
                }
            }
            .setNegativeButton(R.string.main_file_cancel, null)
            .create()

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
        }
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
