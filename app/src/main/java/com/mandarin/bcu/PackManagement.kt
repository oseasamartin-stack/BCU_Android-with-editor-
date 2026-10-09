package com.mandarin.bcu

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mandarin.bcu.androidutil.Definer
import com.mandarin.bcu.androidutil.LocaleManager
import com.mandarin.bcu.androidutil.Revalidater
import com.mandarin.bcu.androidutil.StaticStore
import com.mandarin.bcu.androidutil.io.AContext
import com.mandarin.bcu.androidutil.io.DefineItf
import com.mandarin.bcu.androidutil.pack.PackConflict
import com.mandarin.bcu.androidutil.pack.adapters.PackManagementAdapter
import com.mandarin.bcu.androidutil.supports.LeakCanaryManager
import com.mandarin.bcu.androidutil.supports.SingleClick
import common.CommonStatic
import common.pack.PackData
import common.pack.Source
import common.pack.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat
import java.util.Locale

class PackManagement : AppCompatActivity() {
    companion object {
        var handlingPacks = false
        var needReload = false
    }

    val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode == RESULT_OK) {
            val path = result.data?.data ?: return@registerForActivityResult

            Log.i("PackManagement", "Got URI : $path")

            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)

            val resolver = applicationContext.contentResolver
            val cursor = try {
                resolver.query(path, projection, null, null, null)
            } catch (_: SecurityException) {
                StaticStore.showShortMessage(this, R.string.pack_import_denied)

                return@registerForActivityResult
            } catch (_: FileNotFoundException) {
                StaticStore.showShortMessage(this, R.string.pack_import_nofile)

                return@registerForActivityResult
            } catch (_: IllegalArgumentException) {
                StaticStore.showShortMessage(this, R.string.pack_import_nofile)

                return@registerForActivityResult
            }

            if(cursor != null) {
                if(cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: return@registerForActivityResult

                    if(!name.endsWith(".pack.bcuzip")) {
                        StaticStore.showShortMessage(this, R.string.pack_import_invalid)

                        return@registerForActivityResult
                    }

                    val pack = File(StaticStore.getExternalPack(this), name)

                    if(!pack.exists()) {
                        try {
                            val ins = resolver.openInputStream(path) ?: return@registerForActivityResult
                            val fos = FileOutputStream(pack)

                            pack.createNewFile()

                            showWritingDialog(ins, fos, pack)
                        } catch (_: FileNotFoundException) {
                            StaticStore.showShortMessage(this, R.string.pack_import_nofile)

                            return@registerForActivityResult
                        }
                    } else {
                        StaticStore.fixOrientation(this)

                        val dialog = AlertDialog.Builder(this)

                        dialog.setTitle(R.string.pack_import_exist)
                        dialog.setMessage(R.string.pack_import_exist_msg)

                        dialog.setPositiveButton(R.string.replace) { _, _ ->
                            try {
                                val ins = resolver.openInputStream(path) ?: return@setPositiveButton
                                val fos = FileOutputStream(pack)

                                pack.createNewFile()

                                showWritingDialog(ins, fos, pack)
                            } catch (_: FileNotFoundException) {
                                StaticStore.showShortMessage(this, R.string.pack_import_nofile)

                                return@setPositiveButton
                            }
                        }

                        dialog.setNegativeButton(R.string.main_file_cancel) {_, _ ->
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        }

                        if (!isDestroyed && !isFinishing) {
                            dialog.show()
                        }
                    }


                }

                cursor.close()
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = getSharedPreferences(StaticStore.CONFIG, Context.MODE_PRIVATE)
        val ed: SharedPreferences.Editor

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        if (!shared.contains("initial")) {
            ed = shared.edit()
            ed.putBoolean("initial", true)
            ed.putBoolean("theme", true)
            ed.apply()
        } else {
            if (!shared.getBoolean("theme", false)) {
                setTheme(R.style.AppTheme_night)
            } else {
                setTheme(R.style.AppTheme_day)
            }
        }

        LeakCanaryManager.initCanary(shared, application)

        DefineItf.check(this)

        AContext.check()

        (CommonStatic.ctx as AContext).updateActivity(this)

        setContentView(R.layout.activity_pack_management)

        lifecycleScope.launch {
            //Prepare
            val swipe = findViewById<SwipeRefreshLayout>(R.id.pmanrefresh)
            val list = findViewById<ListView>(R.id.pmanlist)
            val more = findViewById<FloatingActionButton>(R.id.pmanoption)
            val bck = findViewById<FloatingActionButton>(R.id.pmanbck)
            val st = findViewById<TextView>(R.id.status)
            val prog = findViewById<ProgressBar>(R.id.prog)

            StaticStore.setDisappear(list, swipe, more)

            //Load Data
            withContext(Dispatchers.IO) {
                Definer.define(this@PackManagement, { _ -> }, { t -> runOnUiThread { st.text = t }})
            }

            //Load UI
            more.setOnClickListener(object : SingleClick() {
                override fun onSingleClick(v: View?) {
                    // [Editor] Import an existing pack, or create a new editable one
                    val popup = PopupMenu(this@PackManagement, more)

                    popup.menu.add(0, 0, 0, R.string.editor_pack_import)
                    popup.menu.add(0, 1, 1, R.string.editor_pack_create)

                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            0 -> {
                                val intent = Intent(Intent.ACTION_GET_CONTENT)
                                intent.addCategory(Intent.CATEGORY_DEFAULT)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.type = "*/*"

                                resultLauncher.launch(Intent.createChooser(intent, "Choose Directory"))
                            }
                            1 -> showCreatePackDialog(list)
                        }

                        true
                    }

                    popup.show()
                }
            })

            val packList = ArrayList<PackData.UserPack>()

            for(pack in UserProfile.getUserPacks()) {
                packList.add(pack)
            }

            list.adapter = PackManagementAdapter(this@PackManagement, packList)

            prog.isIndeterminate = true

            swipe.setColorSchemeColors(StaticStore.getAttributeColor(this@PackManagement, R.attr.colorAccent))

            swipe.setOnRefreshListener {
                handlingPacks = true
                StaticStore.fixOrientation(this@PackManagement)

                reloadPack(swipe, list)
            }

            bck.setOnClickListener {
                if(!handlingPacks && !needReload) {
                    val intent = Intent(this@PackManagement, MainActivity::class.java)

                    startActivity(intent)

                    finish()

                    return@setOnClickListener
                }

                val dialog = Dialog(this@PackManagement)

                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setContentView(R.layout.loading_dialog)
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) ?: return@setOnClickListener

                val v = dialog.window?.decorView ?: return@setOnClickListener

                val title = v.findViewById<TextView>(R.id.loadtitle)
                val progress = v.findViewById<TextView>(R.id.loadprogress)

                progress.visibility = View.GONE

                title.text = getString(R.string.pack_reload)

                dialog.setCancelable(false)

                if (!isDestroyed && !isFinishing) {
                    dialog.show()
                }

                CoroutineScope(Dispatchers.IO).launch {
                    StaticStore.resetUserPacks()

                    Definer.define(this@PackManagement, {_ -> }, this@PackManagement::updateText)

                    Locale.getDefault().language
                    Revalidater.validate(this@PackManagement)

                    if (isSafeToDismiss(dialog))
                        dialog.dismiss()

                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    handlingPacks = false

                    withContext(Dispatchers.Main) {
                        if(PackConflict.conflicts.isNotEmpty()) {
                            val intent = Intent(this@PackManagement, PackConflictSolve::class.java)

                            startActivity(intent)

                            finish()
                        } else {
                            val intent = Intent(this@PackManagement, MainActivity::class.java)

                            startActivity(intent)

                            finish()
                        }
                    }

                    needReload = false
                }
            }

            onBackPressedDispatcher.addCallback(this@PackManagement, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    bck.performClick()
                }
            })

            StaticStore.setDisappear(st, prog)
            StaticStore.setAppear(list, more, swipe)
        }
    }

    override fun onResume() {
        AContext.check()

        if(CommonStatic.ctx is AContext)
            (CommonStatic.ctx as AContext).updateActivity(this)

        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()

        StaticStore.toast = null
    }

    override fun attachBaseContext(newBase: Context) {
        val shared = newBase.getSharedPreferences(StaticStore.CONFIG, Context.MODE_PRIVATE)
        val lang = shared?.getInt("Language",0) ?: 0

        val config = Configuration()
        var language = StaticStore.lang[lang]
        var country = ""

        if(language == "") {
            language = Resources.getSystem().configuration.locales.get(0).language
            country = Resources.getSystem().configuration.locales.get(0).country
        }

        val loc = if(country.isNotEmpty()) {
            Locale(language, country)
        } else {
            Locale(language)
        }

        config.setLocale(loc)
        applyOverrideConfiguration(config)
        super.attachBaseContext(LocaleManager.langChange(newBase,shared?.getInt("Language",0) ?: 0))
    }

    private fun showWritingDialog(ins: InputStream, fos: FileOutputStream, pack: File) {
        needReload = true

        val swipe = findViewById<SwipeRefreshLayout>(R.id.pmanrefresh)
        val list = findViewById<ListView>(R.id.pmanlist)

        StaticStore.fixOrientation(this)
        handlingPacks = true

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.loading_dialog)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                ?: return
        val v = dialog.window?.decorView ?: return

        val title = v.findViewById<TextView>(R.id.loadtitle)
        val progress = v.findViewById<TextView>(R.id.loadprogress)

        title.text = getString(R.string.pack_import_importing).replace("_", pack.name)

        dialog.setCancelable(false)

        if (!isDestroyed && !isFinishing) {
            dialog.show()
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val total = ins.available().toLong()
                var prog = 0L
                val df = DecimalFormat("#.##")

                val b = ByteArray(65536)
                var len: Int

                while(ins.read(b).also { len = it } != -1) {
                    fos.write(b, 0, len)

                    prog += len

                    val msg = if(total >= 50000000) {
                        "${byteToMB(prog, df)} MB / ${byteToMB(total, df)} MB (${(prog*100.0/total).toInt()}%)"
                    } else {
                        "${byteToKB(prog, df)} KB / ${byteToKB(total, df)} KB (${(prog*100.0/total).toInt()}%)"
                    }

                    runOnUiThread {
                        progress.text = msg
                    }
                }

                ins.close()
                fos.close()

                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

                handlingPacks = false

                withContext(Dispatchers.Main) {
                    title.setText(R.string.pack_reload)

                    progress.visibility = View.GONE
                }

                StaticStore.resetUserPacks()

                Definer.define(this@PackManagement, { _ -> }, this@PackManagement::updateText)

                Locale.getDefault().language
                Revalidater.validate(this@PackManagement)

                if (isSafeToDismiss(dialog))
                    dialog.dismiss()

                val packList = ArrayList<PackData.UserPack>()

                for(p in UserProfile.getUserPacks()) {
                    packList.add(p)
                }

                withContext(Dispatchers.Main) {
                    list.adapter = PackManagementAdapter(this@PackManagement, packList)
                }

                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                handlingPacks = false

                withContext(Dispatchers.Main) {
                    swipe?.isRefreshing = false

                    if(PackConflict.conflicts.isNotEmpty()) {
                        StaticStore.showShortSnack(findViewById(R.id.pmanlayout), R.string.pack_manage_warn)
                    }
                }
            }
        }
    }

    private fun reloadPack(swipe: SwipeRefreshLayout?, list: ListView) {
        needReload = true

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.loading_dialog)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) ?: return
        val v = dialog.window?.decorView ?: return

        val title = v.findViewById<TextView>(R.id.loadtitle)
        val progress = v.findViewById<TextView>(R.id.loadprogress)

        progress.visibility = View.GONE

        title.text = getString(R.string.pack_reload)

        dialog.setCancelable(false)

        if (!isDestroyed && !isFinishing) {
            dialog.show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            StaticStore.resetUserPacks()

            Definer.define(this@PackManagement, { _ -> }, this@PackManagement::updateText)

            Locale.getDefault().language
            Revalidater.validate(this@PackManagement)

            if (isSafeToDismiss(dialog))
                dialog.dismiss()

            val packList = ArrayList<PackData.UserPack>()

            for(pack in UserProfile.getUserPacks()) {
                packList.add(pack)
            }

            runOnUiThread {
                list.adapter = PackManagementAdapter(this@PackManagement, packList)
            }

            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            handlingPacks = false

            runOnUiThread {
                swipe?.isRefreshing = false
            }

            if(PackConflict.conflicts.isNotEmpty()) {
                StaticStore.showShortSnack(findViewById(R.id.pmanlayout), R.string.pack_manage_warn)
            }
        }
    }

    // [Editor] Ask for a name and author, then create an empty workspace pack
    private fun showCreatePackDialog(list: ListView) {
        val pad = (16 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(pad, pad / 2, pad, 0)

        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.editor_pack_name)
        nameInput.setSingleLine()

        val authorInput = EditText(this)
        authorInput.hint = getString(R.string.editor_pack_author)
        authorInput.setSingleLine()

        layout.addView(nameInput)
        layout.addView(authorInput)

        val dialog = AlertDialog.Builder(this)

        dialog.setTitle(R.string.editor_pack_create)
        dialog.setView(layout)

        dialog.setPositiveButton(R.string.editor_create) { _, _ ->
            createPack(nameInput.text.toString().trim(), authorInput.text.toString().trim(), list)
        }

        dialog.setNegativeButton(R.string.main_file_cancel, null)

        if (!isDestroyed && !isFinishing) {
            dialog.show()
        }
    }

    // [Editor] Same steps the PC version uses: random ID -> initJsonPack -> save workspace
    private fun createPack(name: String, author: String, list: ListView) {
        lifecycleScope.launch {
            val pack: PackData.UserPack? = withContext(Dispatchers.IO) {
                try {
                    var id = Source.Workspace.generatePackID()

                    while (UserProfile.getUserPack(id) != null || CommonStatic.ctx.getWorkspaceFile("./$id").exists()) {
                        id = Source.Workspace.generatePackID()
                    }

                    val p = UserProfile.initJsonPack(id)

                    if (p != null) {
                        if (name.isNotEmpty())
                            p.desc.names.put(name)

                        if (author.isNotEmpty())
                            p.desc.author = author

                        UserProfile.profile().packlist.add(p)

                        Source.Workspace.saveWorkspace()
                    }

                    p
                } catch (e: Exception) {
                    Log.e("PackManagement", "Failed to create pack", e)

                    null
                }
            }

            if (pack == null) {
                StaticStore.showShortMessage(this@PackManagement, R.string.editor_pack_create_fail)

                return@launch
            }

            needReload = true

            list.adapter = PackManagementAdapter(this@PackManagement, ArrayList(UserProfile.getUserPacks()))

            StaticStore.showShortMessage(this@PackManagement, getString(R.string.editor_pack_created).replace("_", pack.sid))
        }
    }

    private fun byteToKB(bytes: Long, df: DecimalFormat) : String {
        return df.format(bytes.toDouble()/1024.0)
    }

    private fun byteToMB(bytes: Long, df: DecimalFormat) : String {
        return df.format(bytes.toDouble()/(1024.0 * 1024))
    }

    private fun updateText(info: String) {
        val st = findViewById<TextView>(R.id.status)

        runOnUiThread {
            st.text = StaticStore.getLoadingText(this, info)
        }
    }

    private fun isSafeToDismiss(dialog: Dialog) : Boolean {
        val window = dialog.window ?: return false

        return window.decorView.parent != null
    }
}