package com.attentiontop.basselai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var dbHelper: AppDatabase
    private lateinit var inputRequest: EditText
    private lateinit var btnAnalyze: Button
    private lateinit var txtResult: TextView
    private lateinit var txtStatus: TextView

    private var currentProjectId: Long = -1L
    private var isNetworkCallInProgress: Boolean = false

    // نطاق مستقل لإدارة العمليات الخلفية بأمان تام دون الحاجة لمكتبات خارجية معقدة
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // بناء الواجهة أولاً لضمان فتح الشاشة فوراً
        setupProgrammaticUI()

        try {
            dbHelper = AppDatabase(this)
            restoreLatestProject()
        } catch (e: Exception) {
            txtStatus.text = "تنبيه في قاعدة البيانات: ${e.message}"
        }

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

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel() // إيقاف أي عمليات معلقة عند إغلاق التطبيق
    }

    private fun setupProgrammaticUI() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#121212"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(this).apply {
            text = "Bassel AI - الأرشيف والمنسق الذكي"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }

        txtStatus = TextView(this).apply {
            text = "الحالة: جاهز"
            textSize = 14f
            setTextColor(Color.parseColor("#03DAC5"))
            setPadding(0, 0, 0, dp(8))
        }

        inputRequest = EditText(this).apply {
            hint = "اكتب طلبك أو فكرتك هنا..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            minHeight = dp(52)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        btnAnalyze = Button(this).apply {
            text = "تحليل وتنفيذ عبر Bassel AI"
            setBackgroundColor(Color.parseColor("#6200EE"))
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(12)
            layoutParams = params
        }

        val scrollView = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            params.topMargin = dp(16)
            layoutParams = params
        }

        txtResult = TextView(this).apply {
            text = "ستظهر نتائج التحليل وخطط العمل هنا..."
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true) // يسمح لك بنسخ النص الناتج بسهولة من الهاتف
        }

        scrollView.addView(txtResult)

        rootLayout.addView(titleView)
        rootLayout.addView(txtStatus)
        rootLayout.addView(inputRequest)
        rootLayout.addView(btnAnalyze)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    private fun restoreLatestProject() {
        val project = dbHelper.getLatestProject()
        if (project != null) {
            currentProjectId = project.id
            when (project.status) {
                AppDatabase.STATUS_ANALYSIS -> {
                    txtStatus.text = "الحالة: جاري التحليل..."
                }
                AppDatabase.STATUS_WAITING_FOR_APPROVAL -> {
                    txtResult.text = "استعادة المشروع (ID: ${project.id}):\n\n${project.plan}\n\n[الخطة تنتظر موافقتك الآن]"
                    txtStatus.text = "الحالة: بانتظار الموافقة"
                }
                AppDatabase.STATUS_APPROVED -> {
                    txtResult.text = "المشروع (ID: ${project.id}) مُعتمد وجاهز للتنفيذ."
                    txtStatus.text = "الحالة: معتمد"
                }
                else -> {
                    txtStatus.text = "الحالة: ${project.status}"
                }
            }
        }
    }

    private fun startOrchestratorWorkflow(prompt: String) {
        isNetworkCallInProgress = true
        try {
            currentProjectId = dbHelper.createProject(prompt)
        } catch (e: Exception) {
            txtResult.text = "خطأ في حفظ المشروع محلياً: ${e.message}"
            isNetworkCallInProgress = false
            return
        }

        txtStatus.text = "الحالة: جاري التحليل وتوجيه المهام..."
        txtResult.text = "الطلب: $prompt\n\n[جاري العمل خلف الكواليس عبر النماذج الذكية...]"

        mainScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var planOutput: String? = null
            var usedProvider = "gemini"
            var errorMsg = ""

            val geminiKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Exception) { "" }
            if (geminiKey.isNotEmpty() && geminiKey != "YOUR_GEMINI_API_KEY") {
                try {
                    planOutput = callGeminiApi(prompt, geminiKey)
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Gemini Error"
                }
            } else {
                errorMsg = "مفتاح Gemini غير متوفر"
            }

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
                    try {
                        dbHelper.logAttempt(currentProjectId, usedProvider, "planner", planOutput, "", duration)
                        dbHelper.savePlanAndWaitApproval(currentProjectId, planOutput)
                    } catch (_: Exception) {}

                    txtResult.text = "--- خطة العمل المقترحة (عبر $usedProvider) ---\n\n$planOutput"
                    txtStatus.text = "الحالة: بانتظار الموافقة"
                } else {
                    try {
                        dbHelper.logAttempt(currentProjectId, usedProvider, "planner", "", errorMsg, duration)
                        dbHelper.updateProjectStatus(currentProjectId, AppDatabase.STATUS_FAILED)
                    } catch (_: Exception) {}

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
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "بصفتك المنسق الرئيسي لنظام Bassel AI، قم بتحليل الطلب التالي وضع خطة عمل مفصلة وواضحة: $prompt"))
                    })
                })
            })
        }

        connection.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

        if (connection.responseCode != 200) {
            val err = connection.errorStream?.use { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            throw RuntimeException("HTTP ${connection.responseCode}: $err")
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
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "بصفتك محرك التنفيذ المساعد لنظام Bassel AI، حلل الطلب التالي واقترح خطوات دقيقة: $prompt")
                })
            })
        }

        connection.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

        if (connection.responseCode != 200) {
            val err = connection.errorStream?.use { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            throw RuntimeException("Groq HTTP ${connection.responseCode}: $err")
        }

        val responseString = connection.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }
        val jsonResponse = JSONObject(responseString)
        val choices = jsonResponse.optJSONArray("choices") ?: throw RuntimeException("استجابة غير صالحة من Groq")
        
        return choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "") ?: throw RuntimeException("محتوى فارغ من Groq")
    }
}
