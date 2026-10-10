package com.mandarin.bcu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mandarin.bcu.androidutil.StaticStore
import com.mandarin.bcu.androidutil.fakeandroid.CVGraphics
import com.mandarin.bcu.androidutil.io.AContext
import com.mandarin.bcu.androidutil.io.DefineItf
import common.CommonStatic
import common.pack.Source
import common.pack.UserProfile
import common.system.P
import common.util.anim.AnimCE
import common.util.anim.AnimU
import common.util.anim.EAnimD
import common.util.anim.MaAnim
import common.util.anim.MaModel

/**
 * [Editor] Model editor: how the image parts are assembled into a character
 * (parent tree, position, pivot, scale, rotation, opacity, layer).
 * Opened from the enemy / form editor with the same extras as EnemyEditor.
 */
class ModelEditor : AppCompatActivity() {

    private lateinit var anim: AnimCE

    // Working copy; written back on Save
    private lateinit var model: MaModel
    private var selected = 0
    private var changed = false

    private lateinit var preview: ModelView
    private lateinit var pieceSpinner: Spinner
    private lateinit var pieceAdapter: ArrayAdapter<String>
    private lateinit var parentSpinner: Spinner
    private lateinit var imageSpinner: Spinner
    private lateinit var nameField: EditText
    private lateinit var highlight: CheckBox

    /** Number fields: model column -> field */
    private val numFields = LinkedHashMap<Int, EditText>()

    /** Parent spinner position -> piece index (-1 = no parent) */
    private var parentChoices = listOf<Int>()

    private var filling = false
    private var textColor = 0

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

        val a = findAnim()

        if (a == null) {
            StaticStore.showShortMessage(this, R.string.editor_enemy_missing)
            finish()
            return
        }

        anim = a

        try {
            anim.check()
            model = anim.mamodel.clone()
        } catch (e: Exception) {
            Log.e("ModelEditor", "Failed to load animation", e)
            StaticStore.showShortMessage(this, R.string.editor_import_fail)
            finish()
            return
        }

        textColor = StaticStore.getAttributeColor(this, R.attr.TextPrimary)

        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })
    }

    private fun findAnim(): AnimCE? {
        val pack = intent.getStringExtra(EnemyEditor.EXTRA_PACK)?.let { UserProfile.getUserPack(it) } ?: return null

        if (!pack.editable)
            return null

        val a = if (intent.hasExtra(EnemyEditor.EXTRA_UNIT)) {
            val u = pack.units.getRaw(intent.getIntExtra(EnemyEditor.EXTRA_UNIT, -1)) ?: return null
            val fi = intent.getIntExtra(EnemyEditor.EXTRA_FORM, -1)
            if (fi !in u.forms.indices) return null
            u.forms[fi].anim
        } else {
            pack.enemies.getRaw(intent.getIntExtra(EnemyEditor.EXTRA_INDEX, -1))?.anim
        }

        val ce = a as? AnimCE ?: return null

        return if (ce.id.pack == pack.sid) ce else null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(StaticStore.getAttributeColor(this, R.attr.backgroundPrimary))

        preview = ModelView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val scroll = ScrollView(this)
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(8), dp(12), dp(8))
        scroll.addView(panel)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val help = TextView(this)
        help.text = getString(R.string.editor_mm_help)
        help.setTextColor(textColor)
        help.alpha = 0.7f
        panel.addView(help)

        highlight = CheckBox(this)
        highlight.text = getString(R.string.editor_mm_highlight)
        highlight.setTextColor(textColor)
        highlight.isChecked = true
        highlight.setOnCheckedChangeListener { _, _ -> preview.rebuild() }
        panel.addView(highlight)

        pieceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ArrayList<String>())
        pieceSpinner = Spinner(this)
        pieceSpinner.adapter = pieceAdapter
        pieceSpinner.onItemSelectedListener = itemListener { pos -> if (pos != selected) select(pos) }
        panel.addView(pieceSpinner)

        label(panel, R.string.editor_name)
        nameField = EditText(this)
        nameField.setSingleLine()
        nameField.setTextColor(textColor)
        nameField.addTextChangedListener(watcher {
            if (!filling) {
                model.strs0[selected] = nameField.text.toString().replace(",", " ")
                changed = true
                refreshPieceLabels()
            }
        })
        panel.addView(nameField)

        label(panel, R.string.editor_mm_parent)
        parentSpinner = Spinner(this)
        parentSpinner.onItemSelectedListener = itemListener { pos ->
            if (!filling && pos in parentChoices.indices && model.parts[selected][0] != parentChoices[pos]) {
                model.parts[selected][0] = parentChoices[pos]
                changed = true
                refreshPieceLabels()
                preview.rebuild()
            }
        }
        panel.addView(parentSpinner)

        label(panel, R.string.editor_mm_image)
        imageSpinner = Spinner(this)
        val imgLabels = (0 until anim.imgcut.n).map { i ->
            val nm = anim.imgcut.strs.getOrNull(i)?.trim().orEmpty()
            getString(R.string.editor_ic_part).replace("_", i.toString()) + if (nm.isEmpty()) "" else " – $nm"
        }
        imageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, imgLabels)
        imageSpinner.onItemSelectedListener = itemListener { pos ->
            if (!filling && model.parts[selected][2] != pos) {
                model.parts[selected][2] = pos
                changed = true
                preview.rebuild()
            }
        }
        panel.addView(imageSpinner)

        // Number fields, two per row
        val cols = listOf(
            4 to R.string.editor_mm_x, 5 to R.string.editor_mm_y,
            6 to R.string.editor_mm_pivx, 7 to R.string.editor_mm_pivy,
            8 to R.string.editor_mm_scalex, 9 to R.string.editor_mm_scaley,
            10 to R.string.editor_mm_angle, 11 to R.string.editor_mm_opacity,
            3 to R.string.editor_mm_layer, 12 to R.string.editor_mm_glow
        )

        for (i in cols.indices step 2) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            for (k in i until minOf(i + 2, cols.size))
                numFields[cols[k].first] = numberField(row, getString(cols[k].second), cols[k].first)

            panel.addView(row)
        }

        val note = TextView(this)
        note.text = getString(R.string.editor_mm_units)
        note.setTextColor(textColor)
        note.alpha = 0.7f
        panel.addView(note)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL

        val add = Button(this)
        add.text = getString(R.string.editor_mm_add)
        add.isAllCaps = false
        add.setOnClickListener { addPiece() }

        val remove = Button(this)
        remove.text = getString(R.string.editor_mm_remove)
        remove.isAllCaps = false
        remove.setOnClickListener { removeLastPiece() }

        row1.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(remove, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row1)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL

        val cancel = Button(this)
        cancel.text = getString(R.string.main_file_cancel)
        cancel.setOnClickListener { confirmLeave() }

        val save = Button(this)
        save.text = getString(R.string.editor_save)
        save.setOnClickListener { save() }

        row2.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row2)

        setContentView(root)

        refreshPieceLabels()
        select(0)
    }

    private fun label(panel: LinearLayout, res: Int) {
        val l = TextView(this)
        l.text = getString(res)
        l.setTextColor(textColor)
        l.setPadding(0, dp(6), 0, 0)
        panel.addView(l)
    }

    private fun watcher(onAfter: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onAfter()
    }

    private fun itemListener(onPick: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun numberField(row: LinearLayout, label: String, col: Int): EditText {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        val l = TextView(this)
        l.text = label
        l.setTextColor(textColor)
        box.addView(l)

        val et = EditText(this)
        et.setSingleLine()
        et.setTextColor(textColor)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        et.addTextChangedListener(watcher {
            if (!filling) {
                et.text.toString().toIntOrNull()?.takeIf { it != model.parts[selected][col] }?.let {
                    model.parts[selected][col] = it
                    changed = true
                    preview.rebuild()
                }
            }
        })
        box.addView(et)

        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return et
    }

    /** Depth in the parent tree, for indenting the piece list. */
    private fun depth(i: Int): Int {
        var d = 0
        var p = model.parts[i][0]
        val seen = HashSet<Int>()

        while (p in 0 until model.n && seen.add(p)) {
            d++
            p = model.parts[p][0]
        }

        return d
    }

    private fun refreshPieceLabels() {
        val labels = (0 until model.n).map { i ->
            val nm = model.strs0.getOrNull(i)?.trim().orEmpty()
            "  ".repeat(depth(i)) + getString(R.string.editor_mm_piece).replace("_", i.toString()) + if (nm.isEmpty()) "" else " – $nm"
        }

        pieceAdapter.clear()
        pieceAdapter.addAll(labels)
        pieceAdapter.notifyDataSetChanged()

        if (pieceSpinner.selectedItemPosition != selected)
            pieceSpinner.setSelection(selected)
    }

    /** All pieces that have [i] somewhere above them (a piece can't be attached to these). */
    private fun descendants(i: Int): Set<Int> {
        val out = HashSet<Int>()
        var grew = true

        while (grew) {
            grew = false
            for (k in 0 until model.n)
                if (k !in out && k != i && (model.parts[k][0] == i || model.parts[k][0] in out))
                    grew = out.add(k) || grew
        }

        return out
    }

    private fun select(i: Int) {
        selected = i.coerceIn(0, model.n - 1)

        val p = model.parts[selected]

        filling = true

        if (pieceSpinner.selectedItemPosition != selected)
            pieceSpinner.setSelection(selected)

        nameField.setText(model.strs0.getOrNull(selected) ?: "")

        // Parent choices: none, or any piece that isn't this one or below it
        val blocked = descendants(selected) + selected
        parentChoices = listOf(-1) + (0 until model.n).filter { it !in blocked }

        val parentLabels = parentChoices.map {
            if (it < 0) getString(R.string.editor_mm_noparent)
            else getString(R.string.editor_mm_piece).replace("_", it.toString()) +
                    (model.strs0.getOrNull(it)?.trim()?.let { n -> if (n.isEmpty()) "" else " – $n" } ?: "")
        }

        parentSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, parentLabels)
        parentSpinner.setSelection(maxOf(0, parentChoices.indexOf(p[0])))

        imageSpinner.setSelection(p[2].coerceIn(0, maxOf(0, anim.imgcut.n - 1)))

        for ((col, et) in numFields)
            et.setText(p[col].toString())

        // Spinner callbacks arrive after this returns, so stop ignoring them a moment later
        pieceSpinner.post { filling = false }

        preview.rebuild()
    }

    private fun addPiece() {
        val p = intArrayOf(maxOf(0, selected), -1, 0, model.n, 0, 0, 0, 0, 1000, 1000, 0, 1000, 0, 0)

        model.parts = model.parts + p
        model.strs0 = model.strs0 + "new"
        model.n = model.parts.size

        changed = true
        refreshPieceLabels()
        select(model.n - 1)
    }

    /** Only the last piece can be removed, and only if nothing depends on it. */
    private fun removeLastPiece() {
        if (model.n <= 1) {
            StaticStore.showShortMessage(this, R.string.editor_mm_need_one)
            return
        }

        val last = model.n - 1

        if ((0 until last).any { model.parts[it][0] == last }) {
            StaticStore.showShortMessage(this, R.string.editor_mm_has_children)
            return
        }

        val usedInAnims = anim.anims?.sumOf { ma -> ma?.parts?.count { it.ints[0] == last } ?: 0 } ?: 0

        if (usedInAnims > 0) {
            StaticStore.showShortMessage(this, getString(R.string.editor_mm_in_anims).replace("_", usedInAnims.toString()))
            return
        }

        model.parts = model.parts.copyOf(last).requireNoNulls()
        model.strs0 = model.strs0.copyOf(last).map { it ?: "" }.toTypedArray()
        model.n = last

        changed = true
        refreshPieceLabels()
        select(minOf(selected, last - 1))
    }

    private fun save() {
        try {
            val mm = anim.mamodel

            mm.n = model.n
            mm.parts = Array(model.n) { model.parts[it].clone() }
            mm.strs0 = model.strs0.clone()

            // Fixes out-of-range image numbers and any parent loops
            mm.check(anim)

            Source.SourceAnimSaver(anim.id, anim).saveData()
        } catch (e: Exception) {
            Log.e("ModelEditor", "Failed to save", e)
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
            .setMessage(R.string.editor_mm_discard_msg)
            .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.main_file_cancel, null)
            .show()
    }

    /**
     * Live preview using BCU's own drawing code, so it matches the game.
     * One finger drags the selected piece; two fingers pan and zoom.
     */
    @SuppressLint("ViewConstructor")
    private inner class ModelView(context: Context) : View(context) {

        private val colorPaint = Paint()
        private val bitmapPaint = Paint()
        private val night = !context.getSharedPreferences(StaticStore.CONFIG, Context.MODE_PRIVATE).getBoolean("theme", false)
        private val cv = CVGraphics(Canvas(), colorPaint, bitmapPaint, night)
        private val axisPaint = Paint().apply { color = Color.argb(90, 255, 64, 64); strokeWidth = 2f }

        private var eanim: EAnimD<AnimU.UType>? = null

        private var size = 1f
        private var offX = 0f
        private var offY = 0f

        private var dragging = false
        private var lastX = 0f
        private var lastY = 0f

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                size = (size * d.scaleFactor).coerceIn(0.1f, 10f)
                invalidate()
                return true
            }
        })

        init {
            colorPaint.isFilterBitmap = true
        }

        /** Rebuild the drawn model from the working copy (cheap), highlighting the selected piece. */
        fun rebuild() {
            try {
                val mm = model.clone()

                // Glow makes the selected piece stand out without changing the real data
                if (highlight.isChecked && selected in 0 until mm.n)
                    mm.parts[selected][12] = 1

                eanim = EAnimD(anim, mm, MaAnim(), AnimU.UType.WALK)
            } catch (e: Exception) {
                Log.e("ModelEditor", "Preview failed", e)
                eanim = null
            }

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f + offX
            val cy = height * 2f / 3f + offY

            // Ground line and centre line, to judge positions
            canvas.drawLine(0f, cy, width.toFloat(), cy, axisPaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), axisPaint)

            val e = eanim ?: return

            try {
                cv.setCanvas(canvas)

                val p = P.newP(cx, cy)
                e.draw(cv, p, size)
                P.delete(p)
            } catch (ex: Exception) {
                Log.e("ModelEditor", "Draw failed", ex)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaler.onTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    lastX = ev.x
                    lastY = ev.y
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Two fingers: switch from moving the piece to panning the view
                    dragging = false
                    lastX = (ev.getX(0) + ev.getX(1)) / 2
                    lastY = (ev.getY(0) + ev.getY(1)) / 2
                }

                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount >= 2) {
                        val mx = (ev.getX(0) + ev.getX(1)) / 2
                        val my = (ev.getY(0) + ev.getY(1)) / 2
                        offX += mx - lastX
                        offY += my - lastY
                        lastX = mx
                        lastY = my
                        invalidate()
                    } else if (dragging) {
                        // Move the selected piece. Exact when its parents aren't rotated or scaled.
                        val dx = Math.round((ev.x - lastX) / size)
                        val dy = Math.round((ev.y - lastY) / size)

                        if (dx != 0 || dy != 0) {
                            val part = model.parts[selected]
                            part[4] += dx
                            part[5] += dy
                            changed = true

                            lastX += dx * size
                            lastY += dy * size

                            filling = true
                            numFields[4]?.setText(part[4].toString())
                            numFields[5]?.setText(part[5].toString())
                            filling = false

                            rebuild()
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }

            return true
        }
    }
}
