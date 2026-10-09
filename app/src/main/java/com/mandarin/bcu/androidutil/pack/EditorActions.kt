package com.mandarin.bcu.androidutil.pack

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import com.mandarin.bcu.PackManagement
import com.mandarin.bcu.R
import com.mandarin.bcu.androidutil.StaticStore
import common.battle.data.CustomEnemy
import common.pack.Identifier
import common.pack.PackData
import common.pack.Source
import common.pack.UserProfile
import common.util.Data
import common.util.anim.AnimCE
import common.util.lang.MultiLangCont
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
        val all = UserProfile.getBCData().enemies.list.filterNotNull()
        val labels = all.map { "${Data.trio(it.id.id)} - ${enemyName(it)}" }

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
            .setTitle(R.string.editor_add_enemy)
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
            val src = shownEnemies[position]

            dialog.dismiss()

            addEnemyCopy(ac, pack, src)
        }

        if (!ac.isDestroyed && !ac.isFinishing) {
            dialog.show()
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

    private fun enemyName(e: Enemy): String {
        return try {
            MultiLangCont.get(e) ?: e.names.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
