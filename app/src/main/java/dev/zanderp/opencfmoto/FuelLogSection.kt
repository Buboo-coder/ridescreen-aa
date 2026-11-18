// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * "Fuel log" block of the fuel page: averages card, "new fill-up" form and history. All data stays
 * on the phone ([FuelLog]). Add [view] to any vertical layout.
 */
class FuelLogSection(private val act: AppCompatActivity) {

    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var dateMs = System.currentTimeMillis()

    private lateinit var statAvg: TextView
    private lateinit var statCost: TextView
    private lateinit var statHint: TextView
    private lateinit var dateBtn: MaterialButton
    private lateinit var odoEdit: EditText
    private lateinit var litersEdit: EditText
    private lateinit var priceEdit: EditText
    private lateinit var historyBox: LinearLayout

    init {
        build()
        render()
    }

    private fun color(id: Int) = ContextCompat.getColor(act, id)
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun card(): MaterialCardView = MaterialCardView(act).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        radius = dp(18).toFloat()
        setCardBackgroundColor(color(R.color.surface))
        setContentPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun sectionTitle(res: Int) = TextView(act).apply {
        setText(res)
        textSize = 18f
        setTextColor(color(R.color.text_primary))
        typeface = ResourcesCompat.getFont(act, R.font.archivo_black)
        setPadding(0, dp(22), 0, dp(2))
    }

    private fun smallLabel(res: Int) = TextView(act).apply {
        setText(res)
        textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setPadding(dp(2), 0, 0, dp(4))
    }

    private fun input(hintRes: Int) = EditText(act).apply {
        setHint(hintRes)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine()
        textSize = 17f
        setTextColor(color(R.color.text_primary))
        setHintTextColor(color(R.color.text_secondary))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color(R.color.bg))
            setStroke(dp(1), (color(R.color.text_secondary) and 0x00FFFFFF) or 0x55000000)
        }
    }

    private fun cell(label: Int, child: View, endGap: Boolean): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(smallLabel(label))
        addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (endGap) marginEnd = dp(10)
        }
    }

    private fun statColumn(label: Int, value: TextView): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(act).apply {
            setText(label)
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), (color(R.color.text_secondary) and 0x00FFFFFF) or 0x66000000)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun statValue() = TextView(act).apply {
        textSize = 22f
        setTextColor(color(R.color.brand_gold))
        typeface = ResourcesCompat.getFont(act, R.font.archivo_black)
        text = "—"
    }

    private fun build() {
        view.addView(sectionTitle(R.string.fuel_log_title))

        // Averages card.
        statAvg = statValue()
        statCost = statValue()
        statHint = TextView(act).apply {
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(10), 0, 0)
        }
        val statsCard = card()
        val statsCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        statsCol.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statColumn(R.string.fuel_log_avg, statAvg))
            addView(statColumn(R.string.fuel_log_cost_km, statCost))
        })
        statsCol.addView(statHint)
        statsCard.addView(statsCol)
        view.addView(statsCard)

        // New fill-up form.
        view.addView(sectionTitle(R.string.fuel_log_new))
        dateBtn = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false
            textSize = 15f
            setOnClickListener { pickDate() }
        }
        odoEdit = input(R.string.fuel_log_odo_hint)
        litersEdit = input(R.string.fuel_log_liters_hint)
        priceEdit = input(R.string.fuel_log_price_hint)
        updateDateButton()
        val form = card()
        val formCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        formCol.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cell(R.string.fuel_log_date, dateBtn, true))
            addView(cell(R.string.fuel_log_odo, odoEdit, false))
        })
        formCol.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cell(R.string.fuel_log_liters, litersEdit, true))
            addView(cell(R.string.fuel_log_price, priceEdit, false))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        formCol.addView(TextView(act).apply {
            setText(R.string.fuel_log_tip)
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(12), 0, dp(8))
        })
        formCol.addView(MaterialButton(act).apply {
            setText(R.string.fuel_log_save)
            isAllCaps = false
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(formCol)
        view.addView(form)

        // History.
        view.addView(sectionTitle(R.string.fuel_log_history))
        historyBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        view.addView(historyBox)
    }

    private fun updateDateButton() {
        dateBtn.text = dateFmt.format(Date(dateMs))
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = dateMs }
        val dlg = DatePickerDialog(act, { _, y, m, d ->
            val picked = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }
            dateMs = picked.timeInMillis
            updateDateButton()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.show()
    }

    private fun num(e: EditText): Double? = e.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun save() {
        val odo = num(odoEdit)
        val liters = num(litersEdit)
        val price = num(priceEdit)
        if (odo == null || liters == null || price == null || odo <= 0 || liters <= 0 || price <= 0) {
            Toast.makeText(act, R.string.fuel_log_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (FuelLog.all(act).any { abs(it.odoKm - odo) < 0.05 }) {
            Toast.makeText(act, R.string.fuel_log_odo_used, Toast.LENGTH_LONG).show()
            return
        }
        FuelLog.add(act, FuelLog.Entry(System.currentTimeMillis(), dateMs, odo, liters, price))
        odoEdit.text.clear(); litersEdit.text.clear(); priceEdit.text.clear()
        dateMs = System.currentTimeMillis()
        updateDateButton()
        try {
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Exception) {}
        odoEdit.clearFocus(); litersEdit.clearFocus(); priceEdit.clearFocus()
        Toast.makeText(act, R.string.fuel_log_saved, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        val sorted = FuelLog.all(act)
        val st = FuelLog.stats(sorted)
        val loc = Locale.getDefault()
        if (st == null) {
            statAvg.text = "—"
            statCost.text = "—"
            statHint.setText(R.string.fuel_log_need_more)
            statHint.visibility = View.VISIBLE
        } else {
            statAvg.text = String.format(loc, "%.1f %s", st.kmPerL, act.getString(R.string.fuel_log_kml))
            statCost.text = String.format(loc, "%.2f %s", st.costPerKm, act.getString(R.string.fuel_log_cost_unit))
            statHint.visibility = View.GONE
        }
        // Hint = last odometer so the rider sees where the next number should start.
        odoEdit.hint = sorted.lastOrNull()?.let { String.format(loc, "%,.0f", it.odoKm) } ?: act.getString(R.string.fuel_log_odo_hint)

        historyBox.removeAllViews()
        if (sorted.isEmpty()) {
            historyBox.addView(card().apply {
                addView(TextView(act).apply {
                    setText(R.string.fuel_log_empty)
                    textSize = 15f
                    setTextColor(color(R.color.text_secondary))
                })
            })
            return
        }
        for (i in sorted.indices.reversed()) historyBox.addView(historyRow(sorted, i))
    }

    private fun historyRow(sorted: List<FuelLog.Entry>, i: Int): View {
        val e = sorted[i]
        val loc = Locale.getDefault()
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(act).apply {
            text = act.getString(R.string.fuel_log_row_date_odo, dateFmt.format(Date(e.dateMs)), String.format(loc, "%,.0f", e.odoKm))
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(act).apply {
            text = act.getString(R.string.fuel_log_row_detail, e.liters, e.pricePerL, e.total)
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        FuelLog.kmPerLAt(sorted, i)?.let { eff ->
            right.addView(TextView(act).apply {
                text = act.getString(R.string.fuel_log_row_eff, eff)
                textSize = 14f
                gravity = Gravity.END
                setTextColor(color(R.color.brand_gold))
            })
        }
        right.addView(TextView(act).apply {
            setText(R.string.fuel_log_delete)
            textSize = 13f
            gravity = Gravity.END
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(8), dp(8), 0, dp(2))
            setOnClickListener { confirmDelete(e) }
        })
        row.addView(right)
        return card().apply { addView(row) }
    }

    private fun confirmDelete(e: FuelLog.Entry) {
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.fuel_log_delete_q)
            .setMessage(act.getString(R.string.fuel_log_row_date_odo, dateFmt.format(Date(e.dateMs)), String.format(Locale.getDefault(), "%,.0f", e.odoKm)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.fuel_log_delete) { _, _ ->
                FuelLog.remove(act, e.id)
                render()
            }
            .show()
    }
}
