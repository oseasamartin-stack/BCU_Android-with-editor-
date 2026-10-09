package com.mandarin.bcu.androidutil.pack.adapters

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mandarin.bcu.R
import com.mandarin.bcu.androidutil.StaticStore
import com.mandarin.bcu.androidutil.pack.EditorActions
import com.mandarin.bcu.androidutil.supports.SingleClick
import common.pack.PackData
import common.pack.Source
import common.pack.UserProfile
import java.io.File
import java.text.DecimalFormat

class PackManagementAdapter(private val ac: Activity, private val pList: ArrayList<PackData.UserPack>) : ArrayAdapter<PackData.UserPack>(ac, R.layout.ability_layout, pList) {
    companion object {
        // [Editor] Menu ID for "Add enemy"
        const val MENU_ADD_ENEMY = 1001
        const val MENU_EDIT_ENEMIES = 1002
    }

    class ViewHolder(v: View) {
        val id = v.findViewById<TextView>(R.id.pmanid)!!
        val name = v.findViewById<TextView>(R.id.pmanname)!!
        val desc = v.findViewById<TextView>(R.id.pmandesc)!!
        val more = v.findViewById<FloatingActionButton>(R.id.pmanmore)!!
    }

    var dialog = AlertDialog.Builder(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder: ViewHolder
        val row: View

        if(convertView == null) {
            val inf = LayoutInflater.from(context)

            row = inf.inflate(R.layout.pack_manage_list_layout, parent, false)
            holder = ViewHolder(row)

            row.tag = holder
        } else {
            row = convertView

            holder = row.tag as ViewHolder
        }

        val p = pList[position]

        val title = if(p.desc.author == null || p.desc.author.isBlank()) {
            p.sid
        } else {
            p.sid + " [${p.desc.author}]"
        }

        holder.id.text = title

        holder.name.text = StaticStore.getPackName(p.sid)

        // [Editor] Workspace (editable) packs have no .bcuzip file
        val f: File? = (p.source as? Source.ZipSource)?.packFile
        val isWorkspace = p.source is Source.Workspace

        if (f != null) {
            if(!f.exists()) {
                Log.w("PackManagementAdapter", "File ${f.absolutePath} not existing")

                return row
            }

            holder.desc.text = "${f.name} (${byteToMB(f.length())}MB)"
        } else if (isWorkspace) {
            holder.desc.text = context.getString(R.string.editor_pack_workspace)
        } else {
            holder.desc.text = ""
        }

        val popup = PopupMenu(context, holder.more)
        val menu = popup.menu

        popup.menuInflater.inflate(R.menu.pack_list_option_menu, menu)

        // [Editor] Extra options for editable packs
        if (isWorkspace) {
            menu.add(0, MENU_ADD_ENEMY, 2, R.string.editor_add_enemy)
            menu.add(0, MENU_EDIT_ENEMIES, 3, R.string.editor_edit_enemies)
        }

        popup.setOnMenuItemClickListener {
            when(it.itemId) {
                MENU_ADD_ENEMY -> {
                    EditorActions.showAddEnemyDialog(ac, p)
                }
                MENU_EDIT_ENEMIES -> {
                    EditorActions.showEditEnemiesDialog(ac, p)
                }
                R.id.packremove -> {
                    dialog.setTitle(R.string.pack_manage_remove_sure)
                    dialog.setMessage(R.string.pack_manage_remove_msg)

                    dialog.setPositiveButton(R.string.remove) { _, _ ->
                        deletePack(p, f, isWorkspace)

                        rebuildPackList()

                        notifyDataSetChanged()

                        StaticStore.showShortMessage(context, R.string.pack_remove_result)

                        ac.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    }

                    dialog.setNegativeButton(R.string.main_file_cancel) {_, _ ->
                        ac.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    }

                    StaticStore.fixOrientation(ac)

                    if (!ac.isDestroyed && !ac.isFinishing) {
                        dialog.show()
                    }
                }
                R.id.packshare -> {
                    if(f == null || !f.exists()) {
                        StaticStore.showShortMessage(context, R.string.pack_share_notfound)

                        return@setOnMenuItemClickListener  false
                    }


                    val uri = FileProvider.getUriForFile(context,"com.mandarin.bcu.provider",f)

                    val intent = Intent()

                    intent.action = Intent.ACTION_SEND
                    intent.type = "*/*"
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    intent.putExtra(Intent.EXTRA_STREAM, uri)

                    val i = Intent.createChooser(intent, context.getString(R.string.pack_manage_share))

                    ac.startActivity(i)
                }
            }

            false
        }

        menu.getItem(0).isEnabled = f != null
        menu.getItem(1).isEnabled = !cantDelete(p)

        holder.more.setOnClickListener(object : SingleClick() {
            override fun onSingleClick(v: View?) {
                popup.show()
            }
        })

        return row
    }

    override fun getCount(): Int {
        return pList.size
    }

    private fun byteToMB(bytes: Long) : String {
        val df = DecimalFormat("#.##")

        return df.format(bytes.toDouble()/1000000.0)
    }

    private fun deletePack(p: PackData.UserPack, pack: File?, isWorkspace: Boolean) {
        if(pack != null && pack.exists())
            pack.delete()

        val shared = context.getSharedPreferences(StaticStore.PACK, Context.MODE_PRIVATE)

        val editor = shared.edit()

        val mList = ArrayList<File>()

        val fList = File(StaticStore.dataPath+"music/").listFiles() ?: return

        for(f in fList) {
            if(f.name.startsWith("${p.sid}-"))
                mList.add(f)
        }

        for(m in mList) {
            Log.i("Definer::extractMusic", "Deleted music : ${m.absolutePath}")

            m.delete()

            editor.remove(m.name)
        }

        editor.remove(p.sid)

        editor.apply()

        UserProfile.unloadPack(p)

        // [Editor] Workspace packs are folders; remove the folder too
        if (isWorkspace)
            p.source.delete()
    }

    private fun cantDelete(p: PackData.UserPack) : Boolean {
        for(pack in UserProfile.getAllPacks()) {
            pack ?: continue

            if(pack is PackData.DefPack || pack.sid == p.sid)
                continue

            if(pack is PackData.UserPack) {
                for(pid in pack.desc.dependency) {
                    if(pid == p.sid)
                        return true
                }
            }
        }

        return false
    }

    private fun rebuildPackList() {
        pList.clear()

        for(pack in UserProfile.getUserPacks()) {
            pList.add(pack)
        }
    }
}