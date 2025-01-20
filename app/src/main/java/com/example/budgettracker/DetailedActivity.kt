package com.example.budgettracker

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.addTextChangedListener
import androidx.room.Room
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DetailedActivity : Activity() {
    private lateinit var transaction: Transaction

    private lateinit var updateBtn : Button
    private lateinit var labelLayout : TextInputLayout
    private lateinit var amountLayout : TextInputLayout
    private lateinit var dayOfPaymentLayout: TextInputLayout
    private lateinit var labelInput : TextInputEditText
    private lateinit var amountInput : TextInputEditText
    private lateinit var dayOfPaymentInput : TextInputEditText
    private lateinit var closeBtn : ImageButton
    private lateinit var rootView : ConstraintLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detailed)

        labelInput = findViewById(R.id.labelInput)
        labelLayout = findViewById(R.id.labelLayout)
        amountInput = findViewById(R.id.amountInput)
        amountLayout = findViewById(R.id.amountLayout)
        dayOfPaymentInput = findViewById(R.id.dayOfPaymentInput)
        dayOfPaymentLayout = findViewById(R.id.dayOfPaymentLayout)

        transaction = intent.getSerializableExtra("transaction") as Transaction

        labelInput.setText(transaction.label)
        amountInput.setText("%.2f".format(transaction.amount))
        dayOfPaymentInput.setText(transaction.dayOfPayment.toString())

        rootView = findViewById(R.id.rootView)
        rootView.setOnClickListener {
            this.window.decorView.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }

        labelInput.addTextChangedListener {
            updateBtn.visibility = View.VISIBLE
            if (it!!.isNotEmpty()) {
                labelLayout.error = null
            }
        }

        amountInput.addTextChangedListener {
            updateBtn.visibility = View.VISIBLE
            if (it!!.isNotEmpty()) {
                amountLayout.error = null
            }
        }

        dayOfPaymentInput.addTextChangedListener {
            updateBtn.visibility = View.VISIBLE
            if (it!!.isNotEmpty()) {
                dayOfPaymentLayout.error = null
            }
        }

        updateBtn = findViewById(R.id.updateBtn)
        updateBtn.setOnClickListener {
            val label = labelInput.text.toString()
            val amount = amountInput.text.toString().toDoubleOrNull()
            var dayOfPayment = dayOfPaymentInput.text.toString().toIntOrNull()

            if (label.isEmpty()) {
                labelLayout.error = "Wpisz poprawną nazwę"
            } else if (amount == null) {
                amountLayout.error = "Wpisz poprawną kwotę"
            } else if (dayOfPayment == null || (dayOfPayment < 1 || dayOfPayment > 31)) {
                dayOfPaymentLayout.error = "Wpisz poprawny dzień miesiąca"
            } else {
                val transaction = Transaction(transaction.id, label, amount, dayOfPayment)
                update(transaction)
            }
        }

        closeBtn = findViewById(R.id.closeBtn)
        closeBtn.setOnClickListener {
            finish()
        }
    }

    private fun update(transaction : Transaction) {
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "transactions").fallbackToDestructiveMigration().build()

        GlobalScope.launch {
            db.transactionDao().update(transaction)

            WorkManager.getInstance(applicationContext)
                .cancelUniqueWork("payment_${transaction.id}")

            withContext(Dispatchers.Main) {
                scheduleNotification(transaction)
            }

            finish()
        }
    }

    private fun scheduleNotification(transaction: Transaction) {
        val calendar = Calendar.getInstance()
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        val notificationCalendar = calendar.clone() as Calendar
        val notificationDay = transaction.dayOfPayment - 1

        notificationCalendar.set(Calendar.DAY_OF_MONTH, notificationDay)
        notificationCalendar.set(Calendar.HOUR_OF_DAY, 10)
        notificationCalendar.set(Calendar.MINUTE, 0)
        notificationCalendar.set(Calendar.SECOND, 0)

        if (notificationCalendar.timeInMillis < System.currentTimeMillis()) {
            notificationCalendar.add(Calendar.MONTH, 1)
        }

        val delay = notificationCalendar.timeInMillis - System.currentTimeMillis()

        val data = Data.Builder()
            .putString("label", transaction.label)
            .putDouble("amount", transaction.amount)
            .putInt("dayOfPayment", transaction.dayOfPayment)
            .putLong("transactionId", transaction.id.toLong())
            .build()

        val notificationWork = OneTimeWorkRequestBuilder<PaymentNotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork(
                "payment_${transaction.id}",
                ExistingWorkPolicy.REPLACE,
                notificationWork
            )
    }
}