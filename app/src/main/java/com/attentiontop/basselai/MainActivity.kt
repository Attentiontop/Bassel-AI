package com.attentiontop.basselai

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit.database: AppDatabase // Will use dbHelper
    private lateinit var dbHelper: AppDatabase
    private lateinit var inputRequest: EditText
    private lateinit var btnAnalyze: Button
    private lateinit var btnApprove: Button
    private lateinit var btnReject: Button
    private lateinit var txtResult: TextView
    private lateinit var txtStatus: TextView

    private var currentProjectId: Long = -1L
    private var isNetworkCallInProgress: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = AppDatabase(this)

        inputRequest = findViewById(R.id.inputRequest)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        // افترض أن هذه الأزرار موجودة في التخطيط أو سيتم التعامل معها برمجياً
        txtResult = findViewById(R.id.txtResult)
        txtStatus = findViewById(R.id.txtStatus)

        // استعادة آخر مشروع نشط عند فتح التطبيق لمنع ضياع السياق
        restoreLatestProject()

        btnAnalyze.setOnClickListener {
            val prompt = inputRequest.text.toString().trim()
            if (prompt.isEmpty()) {
                txtResult.text = "الرجاء إدخال طلب أو فكرة لـ Bassel AI."
                return@setOnClickListener
            }

            if (isNetworkCallInProgress) {
                txtResult.text = "الرجاء الانتظار، النظام يعالج طلباً حالياً..."
                return@setOnClickListener
            }

            startOrchestratorWorkflow(prompt)
        }
    }

    private fun restoreLatestProject() {
        try {
            val project = dbHelper.getLatestProject()
            if (project != null) {
                currentProjectId = project.id
                when (project.status) {
                    AppDatabase.STATUS_ANALYSIS -> {
                        txtStatus.statusText("الحالة: جاري التحليل...")
                    }
                    AppDatabase.STATUS_WAITING_FOR_APPROVAL -> {
                        txtResult.text = "استعادة المشروع (ID: ${project.id}):\n\n${project.plan}\n\n[الخطة تنتظر موافقتك الآن]"
                        txtStatus.text = "الحالة: بانتظار الموافقة"
                    }
                    AppDatabase.STATUS_APPROVED -> {
                        txtResult.text = "المشروع (ID: ${project.id}) مُعتمد وجاهز للتنفيذ/التصدير."
                        txtStatus.text = "الحالة: معتمد"
                    }
                    else -> {
                        txtStatus.text = "الحالة: ${project.status}"
                    }
                }
            }
        } catch (e: Exception) {
            txtStatus.text = "خطأ في استعادة الحالة المحلية."
        }
    }

    private fun startOrchestratorWorkflow(prompt: String) {
        isNetworkCallInProgress = true
        currentProjectId = dbHelper.createProject(prompt)

        txtStatus.text = "المنسق (Orchestrator): جاري تحليل الطلب وتوجيهه تلقائياً..."
        txtResult.text = "الطلب: $prompt\n\n[جاري العمل خلف الكواليس عبر النماذج الذكية...]"

        lifecycleScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var planOutput: String? = null
            var usedProvider = "gemini"
            var errorMsg = ""

            // 1. المحاولة الأولى عبر Gemini API (العقل الأساسي)
            val geminiKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Exception) { "" }
            if (geminiKey.isNotEmpty() && geminiKey != "YOUR_GEMINI_API_KEY") {
                try {
                    planOutput = callGeminiApi(prompt, geminiKey)
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Gemini Timeout/Error"
                }
            } else {
                errorMsg = "مفتاح Gemini غير متوفر"
            }

            // 2. التحويل التلقائي (Fallback) إلى Groq إذا فشل Gemini أو حدث Timeout
            if (planOutput == null) {
                usedProvider = "groq"
                val groqKey = try { BuildConfig.GROQ_API_KEY } catch (e: Exception) { "" }
                if (groqKey.isNotEmpty() && groqKey != "YOUR_GROQ_API_KEY") {
                    try {
                        planOutput = callGroqApi(prompt, groqKey)
                    } catch (e: Exception) {
                        errorMsg += " | Groq Error: ${e.message}"
                    }
                } else {
                    errorMsg += " | مفتاح Groq غير متوفر"
                }
            }

            val duration = System.currentTimeMillis() - startTime

            withContext(Dispatchers.Main) {
                isNetworkCallInProgress = false
                if (planOutput != null) {
                    // تسجيل المحاولة الناجحة
                    dbHelper.logAttempt(currentProjectId, usedProvider, "planner", planOutput, "", duration)
                    // حفظ الخطة وتحديث الحالة إلى بانتظار الموافقة
                    dbHelper.savePlanAndWaitApproval(currentProjectId, planOutput)

                    txtResult.text = "--- خطة العمل المقترحة (عبر $usedProvider) ---\n\n$planOutput\n\nهل توافق على هذه الخطة؟"
                    txtStatus.text = "الحالة: بانتظار الموافقة"
                } else {
                    // تسجيل المحاولة الفاشلة
                    dbHelper.logAttempt(currentProjectId, usedProvider, "planner", "", errorMsg, duration)
                    dbHelper.updateProjectStatus(currentProjectId, AppDatabase.STATUS_FAILED)

                    txtResult.text = "فشلت عملية المعالجة عبر جميع المحركات.\nالسبب: $errorMsg"
                    txtStatus.text = "الحالة: فشل التحليل"
                }
            }
        }
    }

    private fun callGeminiApi(prompt: String, apiKey: String): String {
        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
        }

        val jsonBody = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().put("text", "بصفتك المنسق الرئيسي لنظام Bassel AI، قم بتحليل الطلب التالي وضع خطة عمل مفصلة وواضحة: $prompt"))
                    })
                })
            })
        }

        connection.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

        if (connection.responseCode != 200) {
            throw RuntimeException("HTTP Error Code: ${connection.responseCode}")
        }

        val responseString = connection.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }
        val jsonResponse = JSONObject(responseString)
        val candidates = jsonResponse.optJSONArray("candidates") ?: throw RuntimeException("استجابة غير صالحة من Gemini")
        
        return candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "") ?: throw RuntimeException("محتوى فارغ من Gemini")
    }

    private fun callGroqApi(prompt: String, apiKey: String): String {
        val urlString = "https://api.groq.com/openai/v1/chat/completions"
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 15000
            readTimeout = 45000
            doOutput = true
        }

        val jsonBody = JSONObject().apply {
            put("model", "llama3-8b-8192")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "بصفتك محرك التنفيذ المساعد لنظام Bassel AI، حلل الطلب التالي واقترح خطوات دقيقة: $prompt")
                })
            })
        }

        connection.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

        if (connection.responseCode != 200) {
            throw RuntimeException("Groq HTTP Error: ${connection.responseCode}")
        }

        val responseString = connection.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }
        val jsonResponse = JSONObject(responseString)
        val choices = jsonResponse.optJSONArray("choices") ?: throw RuntimeException("استجابة غير صالحة من Groq")
        
        return choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "") ?: throw RuntimeException("محتوى فارغ من Groq")
    }
}

// امتداد مساعد لتبسيط تحديث حالة النص إن لم يكن معرفاً
private fun TextView.statusText(text: String) {
    this.text = text
}
