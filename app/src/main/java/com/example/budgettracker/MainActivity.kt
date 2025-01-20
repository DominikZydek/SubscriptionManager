package com.example.budgettracker

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var db : AppDatabase
    private lateinit var deletedTransaction : Transaction
    private lateinit var oldTransactions : List<Transaction>

    private lateinit var transactions: List<Transaction>
    private lateinit var transactionAdapter: TransactionAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private lateinit var recyclerview: RecyclerView
    private lateinit var balance : TextView
    private lateinit var addBtn : FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkNotificationPermission()

        transactions = arrayListOf()

        transactionAdapter = TransactionAdapter(transactions)
        linearLayoutManager = LinearLayoutManager(this)

        db = Room.databaseBuilder(this, AppDatabase::class.java, "transactions").fallbackToDestructiveMigration().build()

        recyclerview = findViewById(R.id.recyclerview)
        recyclerview.apply {
            adapter = transactionAdapter
            layoutManager = linearLayoutManager
        }

        val itemTouchHelper = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                deleteTransaction(transactions[viewHolder.adapterPosition])
            }

        }

        val swipeHelper = ItemTouchHelper(itemTouchHelper)
        swipeHelper.attachToRecyclerView(recyclerview)

        addBtn = findViewById(R.id.addBtn)
        addBtn.setOnClickListener {
            val intent = Intent(this, AddTransactionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun fetchAll() {
        GlobalScope.launch {
            transactions = db.transactionDao().getAll()

            runOnUiThread {
                transactionAdapter.setData(transactions)
                updateDashboard()
            }
        }
    }

    private fun updateDashboard() {
        val totalAmount = transactions.map { it.amount }.sum()

        balance = findViewById(R.id.balance)
        balance.text = "%.2f zł".format(totalAmount)
    }

    private fun undoDelete() {
        GlobalScope.launch {
            db.transactionDao().insertAll(deletedTransaction)

            withContext(Dispatchers.Main) {
                scheduleNotification(deletedTransaction)
            }

            transactions = oldTransactions

            runOnUiThread {
                transactionAdapter.setData(transactions)
                updateDashboard()
            }
        }
    }

    private fun showSnackbar() {
        val view = findViewById<View>(R.id.coordinator)
        val snackbar = Snackbar.make(view, "Usunięto transakcję", Snackbar.LENGTH_LONG)
        snackbar.setAction("Cofnij") {
            undoDelete()
        }   .setActionTextColor(ContextCompat.getColor(this, R.color.red))
            .setTextColor(ContextCompat.getColor(this, R.color.white))
            .show()
    }

    private fun deleteTransaction(transaction: Transaction) {
        deletedTransaction = transaction
        oldTransactions = transactions

        GlobalScope.launch {
            db.transactionDao().delete(transaction)

            WorkManager.getInstance(applicationContext)
                .cancelUniqueWork("payment_${transaction.id}")

            transactions = transactions.filter { it.id != transaction.id }
            runOnUiThread {
                updateDashboard()
                transactionAdapter.setData(transactions)
                showSnackbar()
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    "android.permission.POST_NOTIFICATIONS"
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf("android.permission.POST_NOTIFICATIONS"),
                    1
                )
            }
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

    override fun onResume() {
        super.onResume()
        fetchAll()
    }
}