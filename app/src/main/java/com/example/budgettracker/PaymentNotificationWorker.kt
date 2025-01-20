package com.example.budgettracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class PaymentNotificationWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        try {
            Log.d("Notification", "PaymentNotificationWorker started")

            val label = inputData.getString("label")
            val amount = inputData.getDouble("amount", 0.0)
            val dayOfPayment = inputData.getInt("dayOfPayment", 1)
            val transactionId = inputData.getLong("transactionId", 0)

            if (label == null) {
                Log.e("Notification", "Label is null")
                return Result.failure()
            }

            showNotification(label, amount, dayOfPayment)
            Log.d("Notification", "Notification shown for: $label")

            scheduleNextNotification(label, amount, dayOfPayment, transactionId)
            Log.d("Notification", "Worker completed successfully")

            return Result.success()
        } catch (e: Exception) {
            Log.e("Notification", "Worker failed: ${e.message}")
            return Result.failure()
        }
    }

    private fun scheduleNextNotification(label: String, amount: Double, dayOfPayment: Int, transactionId: Long) {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, 1)
        calendar.set(Calendar.DAY_OF_MONTH, dayOfPayment - 1)
        calendar.set(Calendar.HOUR_OF_DAY, 10)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)

        val delay = calendar.timeInMillis - System.currentTimeMillis()

        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        Log.d("Notification", "Scheduling next notification for $label at ${formatter.format(calendar.time)}")

        val data = Data.Builder()
            .putString("label", label)
            .putDouble("amount", amount)
            .putInt("dayOfPayment", dayOfPayment)
            .putLong("transactionId", transactionId)
            .build()

        val notificationWork = OneTimeWorkRequestBuilder<PaymentNotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "payment_$transactionId",
                ExistingWorkPolicy.REPLACE,
                notificationWork
            )

        Log.d("Notification", "Next notification for $label scheduled (in ${delay / 1000 / 60} minutes)")
    }

    private fun showNotification(label: String, amount: Double, dayOfPayment: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "payments",
                "Payment Reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, "payments")
            .setSmallIcon(R.drawable.ic_payment_notification)
            .setContentTitle("Zbliżający się termin płatności")
            .setContentText("Jutro płatność: $label - %.2f zł".format(amount))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(dayOfPayment, notification)
    }
}